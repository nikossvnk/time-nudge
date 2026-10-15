package io.github.nikossvnk.timenudge;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.text.format.DateFormat;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Owns the running countdown so it keeps going even if the screen or the activity goes away.
 * The UI reads the static fields below; there is only ever one timer at a time.
 *
 * The running timer is also saved in {@link Store} so it survives Android killing the process:
 * the service is sticky, and an exact alarm wakes it for the next announcement, so a restarted
 * service picks up where it left off (and still restores the alarm volume at the end).
 */
public class TimerService extends Service implements TextToSpeech.OnInitListener {
    static final String ACTION_START = "io.github.nikossvnk.timenudge.START";
    static final String ACTION_DONE = "io.github.nikossvnk.timenudge.DONE";
    static final String ACTION_CANCEL = "io.github.nikossvnk.timenudge.CANCEL";
    static final String ACTION_SILENCE = "io.github.nikossvnk.timenudge.SILENCE";
    /** Sent by the backup alarm, and by the UI when it finds a saved timer that isn't running. */
    static final String ACTION_WAKE = "io.github.nikossvnk.timenudge.WAKE";
    static final String EXTRA_MS = "ms";
    static final String EXTRA_LABEL = "label";

    private static final long MINUTE = 60000L;
    /** The looping alarm sound stops by itself after this much overtime (voice reminders continue). */
    private static final long ALARM_MAX_OVERTIME = 2 * MINUTE;
    /** If nobody taps "Done" by now, the run is closed and logged as unconfirmed. */
    private static final long AUTO_END_OVERTIME = 30 * MINUTE;
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "timer";

    // Shared with the UI.
    static volatile boolean running;
    static volatile long startElapsed;
    static volatile long endElapsed;
    static volatile long startWall;
    static volatile String label = "";
    static volatile boolean alarmRinging;
    static volatile Store.Run lastRun;

    private final Handler handler = new Handler();
    private TextToSpeech tts;
    private boolean ttsReady;
    /** Said once the voice is ready, if something had to be said before then. */
    private String pendingSpeech;
    private AudioManager audio;
    private int savedAlarmVolume = -1;
    private ToneGenerator tone;
    private MediaPlayer alarm;
    private PowerManager.WakeLock wakeLock;
    private Vibrator vibrator;

    private List<Long> checkpoints = new ArrayList<Long>();
    private int nextCheckpoint;
    private boolean finalFired;
    private long lastOverMinute;
    private boolean silenced;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            onTick();
            if (running) handler.postDelayed(this, 250);
        }
    };

    private final Runnable resumeAlarm = new Runnable() {
        @Override
        public void run() {
            startAlarm();
        }
    };

    /**
     * Remaining-time moments (ms, largest first) at which to announce, never more than 5 minutes
     * apart. Up to 15 minutes they get denser near the end: 70%, 40%, 20%, 10% remaining. Longer
     * timers announce every 5 minutes left. Both add 2 min, 1 min and 30 s.
     * For 10 minutes that is alerts at 3, 6, 8, 9 and 9.5 minutes elapsed, then the alarm at 10.
     */
    static List<Long> checkpointsFor(long totalMs) {
        TreeSet<Long> set = new TreeSet<Long>(Collections.<Long>reverseOrder());
        if (totalMs > 15 * MINUTE) {
            for (long m = 5 * MINUTE; m < totalMs; m += 5 * MINUTE) set.add(m);
        } else {
            double[] fractions = {0.7, 0.4, 0.2, 0.1};
            for (double f : fractions) {
                long m = Math.round(totalMs * f / MINUTE) * MINUTE;
                if (m >= MINUTE) set.add(m);
            }
            if (totalMs == 15 * MINUTE) set.add(5 * MINUTE);
        }
        if (totalMs >= 5 * MINUTE) {
            set.add(2 * MINUTE);
            set.add(MINUTE);
        }
        if (totalMs >= 2 * MINUTE) set.add(30000L);
        List<Long> out = new ArrayList<Long>();
        for (Long m : set) {
            // Skip anything that would fire within 30 s of starting.
            if (m <= totalMs - 30000L) out.add(m);
        }
        return out;
    }

    /**
     * The latest whole minute of overtime that gets a spoken reminder: every minute up to 10,
     * then every 5 minutes. Returns 0 before the first one.
     */
    static long overtimeMark(long overMinute) {
        if (overMinute > 10) return overMinute - overMinute % 5;
        return overMinute;
    }

    static void start(Context c, long ms, String label) {
        Intent i = new Intent(c, TimerService.class).setAction(ACTION_START)
                .putExtra(EXTRA_MS, ms).putExtra(EXTRA_LABEL, label);
        c.startService(i);
    }

    static void send(Context c, String action) {
        c.startService(new Intent(c, TimerService.class).setAction(action));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        tts = new TextToSpeech(this, this);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TimeNudge:timer");
        if (Build.VERSION.SDK_INT >= 26) {
            // Silent: the service makes its own sounds. Shown on the lock screen with its Done button.
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Running timer",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notificationManager().createNotificationChannel(channel);
        }
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) return;
        int r = tts.setLanguage(Locale.US);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault());
        }
        tts.setAudioAttributes(alarmAudio(AudioAttributes.CONTENT_TYPE_SPEECH));
        ttsReady = true;
        if (pendingSpeech != null && running) speak(pendingSpeech);
        pendingSpeech = null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        // A null intent means Android restarted the service after killing it.
        if (!running && !ACTION_START.equals(action)) restore();
        if (ACTION_START.equals(action)) {
            begin(intent.getLongExtra(EXTRA_MS, 10 * MINUTE), intent.getStringExtra(EXTRA_LABEL));
        } else if (ACTION_DONE.equals(action)) {
            finish(true, true);
        } else if (ACTION_CANCEL.equals(action)) {
            finish(false, false);
        } else if (ACTION_SILENCE.equals(action)) {
            silenced = true;
            handler.removeCallbacks(resumeAlarm);
            stopAlarm();
            saveState();
        } else if (running) {
            onTick();
        } else {
            // Nothing to continue, e.g. the backup alarm of a run that ended. Android 8+ starts the
            // service for that alarm as a foreground service, and reports it as crashed unless it
            // goes to the foreground, even briefly.
            if (ACTION_WAKE.equals(action) && Build.VERSION.SDK_INT >= 26) {
                goForeground();
                stopForeground(true);
            }
            stopSelf();
        }
        return running ? START_STICKY : START_NOT_STICKY;
    }

    private void begin(long ms, String newLabel) {
        // End the old run without stopping the service, which would take the new run down with it.
        if (running) endRun(false, false);
        long now = SystemClock.elapsedRealtime();
        startElapsed = now;
        endElapsed = now + ms;
        startWall = System.currentTimeMillis();
        label = newLabel == null ? "" : newLabel;
        checkpoints = checkpointsFor(ms);
        nextCheckpoint = 0;
        finalFired = false;
        lastOverMinute = 0;
        silenced = false;
        alarmRinging = false;
        lastRun = null;
        running = true;

        savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM);
        setLoudness(0.6f);
        resume();
        speak("Timer started. " + minutesPhrase(ms) + ".");
    }

    /** Continues a run saved before the process was killed. Does nothing if there is none. */
    private void restore() {
        JSONObject a = Store.activeTimer(this);
        if (a == null) return;
        startElapsed = a.optLong("startElapsed");
        endElapsed = a.optLong("endElapsed");
        savedAlarmVolume = a.optInt("savedAlarmVolume", -1);
        if (SystemClock.elapsedRealtime() < startElapsed) {
            // The phone rebooted, so the saved times mean nothing now. Just undo the volume change.
            restoreVolume();
            Store.clearActiveTimer(this);
            return;
        }
        startWall = a.optLong("startWall");
        label = a.optString("label", "");
        checkpoints = checkpointsFor(endElapsed - startElapsed);
        nextCheckpoint = a.optInt("nextCheckpoint");
        finalFired = a.optBoolean("finalFired");
        lastOverMinute = a.optLong("lastOverMinute");
        silenced = a.optBoolean("silenced");
        alarmRinging = false;
        lastRun = null;
        running = true;
        resume();
        if (finalFired) handler.post(resumeAlarm);  // startAlarm() checks it's still within 2 minutes
    }

    /** Starts (or restarts) everything that keeps a run going: wake lock, notification, ticks. */
    private void resume() {
        long left = Math.max(0, endElapsed - SystemClock.elapsedRealtime());
        if (!wakeLock.isHeld()) wakeLock.acquire(left + AUTO_END_OVERTIME + MINUTE);
        goForeground();
        saveState();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    /**
     * Shows the notification and keeps Android from stopping the service. On Android 14+ the type
     * says why it runs: "systemExempted", which alarm and timer apps (those with the exact alarm
     * permission) may use. That permission also lets it play alarm sounds and change the alarm
     * volume from the background on Android 17.
     */
    private void goForeground() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (RuntimeException e) {
            // Android 12+ can refuse this when the app is in the background. The run goes on, and
            // the backup alarm wakes the service for the next announcement if Android stops it.
            notificationManager().notify(NOTIFICATION_ID, n);
        }
    }

    /** Saves the run so a restarted service can continue it, and sets the alarm that wakes it. */
    private void saveState() {
        if (!running) return;
        try {
            Store.saveActiveTimer(this, new JSONObject()
                    .put("startElapsed", startElapsed).put("endElapsed", endElapsed)
                    .put("startWall", startWall).put("label", label)
                    .put("savedAlarmVolume", savedAlarmVolume)
                    .put("nextCheckpoint", nextCheckpoint).put("finalFired", finalFired)
                    .put("lastOverMinute", lastOverMinute).put("silenced", silenced));
        } catch (JSONException ignored) {
        }
        AlarmManager am = alarmManager();
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            // Exact alarms need a permission on Android 12+ (granted at install from Android 13).
            // Without it, an inexact alarm still brings the service back, just up to minutes late.
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextEventElapsed(), wakeIntent());
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextEventElapsed(), wakeIntent());
        }
    }

    /** When the next announcement (or the auto-end) is due. */
    long nextEventElapsed() {
        if (nextCheckpoint < checkpoints.size()) return endElapsed - checkpoints.get(nextCheckpoint);
        if (!finalFired) return endElapsed;
        long m = lastOverMinute + 1;
        while (overtimeMark(m) <= lastOverMinute) m++;
        return endElapsed + Math.min(m * MINUTE, AUTO_END_OVERTIME);
    }

    private AlarmManager alarmManager() {
        return (AlarmManager) getSystemService(ALARM_SERVICE);
    }

    /** Android 8+ only lets an alarm start a background app's service as a foreground service. */
    private PendingIntent wakeIntent() {
        Intent i = new Intent(this, TimerService.class).setAction(ACTION_WAKE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        if (Build.VERSION.SDK_INT >= 26) return PendingIntent.getForegroundService(this, 1, i, flags);
        return PendingIntent.getService(this, 1, i, flags);
    }

    private void onTick() {
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        long remaining = endElapsed - now;
        float progress = Math.min(1f, (now - startElapsed) / (float) Math.max(1, endElapsed - startElapsed));

        // Announce only the latest checkpoint crossed (avoids a backlog if the phone was asleep).
        int crossed = -1;
        while (nextCheckpoint < checkpoints.size() && remaining <= checkpoints.get(nextCheckpoint)) {
            crossed = nextCheckpoint;
            nextCheckpoint++;
        }
        if (crossed >= 0 && remaining > 0) {
            setLoudness(0.6f + 0.4f * progress);
            long cp = checkpoints.get(crossed);
            int urgency = cp <= MINUTE ? 3 : (cp <= 2 * MINUTE || progress >= 0.75f ? 2 : 1);
            alert(urgency, checkpointPhrase(cp));
        }
        if (crossed >= 0) saveState();

        if (remaining <= 0 && !finalFired) {
            finalFired = true;
            setLoudness(1f);
            alert(3, "Time's up!");
            handler.postDelayed(resumeAlarm, 3500);
            notificationManager().notify(NOTIFICATION_ID, buildNotification());
            saveState();
        }

        if (remaining <= 0) {
            long over = -remaining;
            long overMinute = over / MINUTE;
            if (over >= AUTO_END_OVERTIME) {
                finish(true, false);
                return;
            }
            if (alarmRinging && over >= ALARM_MAX_OVERTIME) stopAlarm();
            long mark = overtimeMark(overMinute);
            if (mark >= 1 && mark > lastOverMinute) {
                lastOverMinute = mark;
                stopAlarm();
                handler.removeCallbacks(resumeAlarm);
                alert(3, (mark == 1 ? "One minute" : mark + " minutes") + " over. Time to stop.");
                if (over < ALARM_MAX_OVERTIME) handler.postDelayed(resumeAlarm, 4500);
                saveState();
            }
        }
    }

    private void finish(boolean record, boolean confirmed) {
        endRun(record, confirmed);
        stopForeground(true);
        stopSelf();
    }

    /** Stops the current run (if any) and logs it, but leaves the service itself running. */
    private void endRun(boolean record, boolean confirmed) {
        if (!running) return;
        running = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(resumeAlarm);
        stopAlarm();
        if (tts != null) tts.stop();
        pendingSpeech = null;
        restoreVolume();
        alarmManager().cancel(wakeIntent());
        Store.clearActiveTimer(this);
        if (record) {
            long actual = SystemClock.elapsedRealtime() - startElapsed;
            Store.Run run = new Store.Run(startWall, label, endElapsed - startElapsed, actual, confirmed);
            Store.addRun(this, run);
            lastRun = run;
        } else {
            lastRun = null;
        }
        if (wakeLock.isHeld()) wakeLock.release();
    }

    private void restoreVolume() {
        if (savedAlarmVolume >= 0) {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0);
            savedAlarmVolume = -1;
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        stopAlarm();
        if (tone != null) tone.release();
        if (tts != null) tts.shutdown();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- sound -------------------------------------------------------------------------------

    /** Beeps (more when more urgent), a buzz, then the spoken message. */
    private void alert(int urgency, final String text) {
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            int t = urgency >= 3 ? ToneGenerator.TONE_CDMA_HIGH_PBX_L : ToneGenerator.TONE_PROP_BEEP2;
            for (int i = 0; i < urgency; i++) {
                final int type = t;
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (tone != null) tone.startTone(type, 250);
                    }
                }, i * 350L);
            }
        } catch (RuntimeException ignored) {
            // ToneGenerator can fail on some devices; voice still works.
        }
        if (vibrator != null && vibrator.hasVibrator()) {
            long[] pattern = urgency >= 3 ? new long[]{0, 400, 150, 400, 150, 400}
                    : urgency == 2 ? new long[]{0, 300, 150, 300} : new long[]{0, 300};
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1),
                        alarmAudio(AudioAttributes.CONTENT_TYPE_SONIFICATION));
            } else {
                vibrator.vibrate(pattern, -1);
            }
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                speak(text);
            }
        }, urgency * 350L + 200);
    }

    private void speak(String text) {
        if (!ttsReady) {
            pendingSpeech = text;
            return;
        }
        Bundle params = new Bundle();
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "timenudge");
    }

    /**
     * Speech, beeps, buzzes and the alarm all count as an alarm: they sound in Do Not Disturb, and
     * Android 17 lets an alarm app (with the exact alarm permission) play them from the background.
     */
    private static AudioAttributes alarmAudio(int contentType) {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(contentType).build();
    }

    /** Sets the alarm stream to a fraction of its maximum (never quieter than the user had it). */
    private void setLoudness(float fraction) {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        int v = Math.max(1, Math.round(max * fraction));
        if (savedAlarmVolume > v && fraction < 1f) v = savedAlarmVolume;
        audio.setStreamVolume(AudioManager.STREAM_ALARM, Math.min(max, v), 0);
    }

    private void startAlarm() {
        if (!running || silenced || alarmRinging) return;
        if (SystemClock.elapsedRealtime() - endElapsed >= ALARM_MAX_OVERTIME) return;
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            alarm = new MediaPlayer();
            alarm.setAudioAttributes(alarmAudio(AudioAttributes.CONTENT_TYPE_SONIFICATION));
            alarm.setDataSource(this, uri);
            alarm.setLooping(true);
            alarm.prepare();
            alarm.start();
            alarmRinging = true;
        } catch (Exception e) {
            if (alarm != null) alarm.release();
            alarm = null;
            // Fall back to a long harsh tone if no alarm sound is available.
            try {
                if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
                tone.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 20000);
                alarmRinging = true;
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void stopAlarm() {
        if (alarm != null) {
            try {
                alarm.stop();
            } catch (IllegalStateException ignored) {
            }
            alarm.release();
            alarm = null;
        }
        if (tone != null) tone.stopTone();
        alarmRinging = false;
    }

    // --- text --------------------------------------------------------------------------------

    static String minutesPhrase(long ms) {
        long min = Math.round(ms / (double) MINUTE);
        if (min <= 1) return "1 minute";
        return min + " minutes";
    }

    private static String checkpointPhrase(long remainingMs) {
        if (remainingMs < MINUTE) return "30 seconds.";
        if (remainingMs == MINUTE) return "One minute left. Finish up.";
        if (remainingMs == 2 * MINUTE) return "2 minutes left. Wrap it up.";
        return minutesPhrase(remainingMs) + " left.";
    }

    private NotificationManager notificationManager() {
        return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    /**
     * A live clock (time left, or time over once it's up), the end time, and a Done button so the
     * run can be finished from the lock screen or another app.
     */
    private Notification buildNotification() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, TimerActivity.class), flags);
        PendingIntent done = PendingIntent.getService(this, 2,
                new Intent(this, TimerService.class).setAction(ACTION_DONE), flags);
        long endWall = startWall + (endElapsed - startElapsed);
        String end = DateFormat.getTimeFormat(this).format(new Date(endWall));
        boolean over = finalFired;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        b.setSmallIcon(notificationIcon())
                .setContentTitle(label.isEmpty() ? "Timer" : label)
                .setContentText(over ? "Over time since " + end + ". Tap Done when you finish." : "Ends at " + end)
                .setWhen(endWall)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(!over)
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_lock_idle_alarm, "Done", done);
        // Android 12+ otherwise holds back a new foreground service's notification for 10 seconds.
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    /**
     * The ic_notification icons in res/drawable-...dpi (the app's ring and clock hands). Looked up by
     * name rather than through R, which the tests (compiled without aapt) don't have; they get the
     * stock alarm icon.
     */
    private int notificationIcon() {
        int id = getResources().getIdentifier("ic_notification", "drawable", getPackageName());
        return id != 0 ? id : android.R.drawable.ic_lock_idle_alarm;
    }
}
