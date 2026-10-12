package com.timenudge.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Owns the running countdown so it keeps going even if the screen or the activity goes away.
 * The UI reads the static fields below; there is only ever one timer at a time.
 */
public class TimerService extends Service implements TextToSpeech.OnInitListener {
    static final String ACTION_START = "com.timenudge.app.START";
    static final String ACTION_DONE = "com.timenudge.app.DONE";
    static final String ACTION_CANCEL = "com.timenudge.app.CANCEL";
    static final String ACTION_SILENCE = "com.timenudge.app.SILENCE";
    static final String EXTRA_MS = "ms";
    static final String EXTRA_LABEL = "label";

    private static final long MINUTE = 60000L;
    /** The looping alarm sound stops by itself after this much overtime (voice reminders continue). */
    private static final long ALARM_MAX_OVERTIME = 2 * MINUTE;
    /** Spoken "N minutes late" reminders stop after this much overtime. */
    private static final long VOICE_MAX_OVERTIME = 15 * MINUTE;
    /** If nobody taps "Done" by now, the run is closed and logged as unconfirmed. */
    private static final long AUTO_END_OVERTIME = 30 * MINUTE;

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
     * Remaining-time moments (ms, largest first) at which to announce. They get denser near the
     * end: 70%, 40%, 20%, 10% remaining, plus 2 min, 1 min and 30 s for longer timers.
     * For 10 minutes that is alerts at 3, 6, 8, 9 and 9.5 minutes elapsed, then the alarm at 10.
     */
    static List<Long> checkpointsFor(long totalMs) {
        TreeSet<Long> set = new TreeSet<Long>(Collections.<Long>reverseOrder());
        double[] fractions = {0.7, 0.4, 0.2, 0.1};
        for (double f : fractions) {
            long m = Math.round(totalMs * f / MINUTE) * MINUTE;
            if (m >= MINUTE) set.add(m);
        }
        if (totalMs >= 15 * MINUTE) set.add(5 * MINUTE);
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
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) return;
        int r = tts.setLanguage(Locale.US);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault());
        }
        ttsReady = true;
        if (running && !finalFired && nextCheckpoint == 0) {
            speak("Timer started. " + minutesPhrase(endElapsed - startElapsed) + ".");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
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
        } else if (!running) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void begin(long ms, String newLabel) {
        if (running) finish(false, false);
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
        if (!wakeLock.isHeld()) wakeLock.acquire(ms + AUTO_END_OVERTIME + MINUTE);
        startForeground(1, buildNotification());

        if (ttsReady) speak("Timer started. " + minutesPhrase(ms) + ".");
        handler.removeCallbacks(tick);
        handler.post(tick);
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

        if (remaining <= 0 && !finalFired) {
            finalFired = true;
            setLoudness(1f);
            alert(3, "Time's up!");
            handler.postDelayed(resumeAlarm, 3500);
        }

        if (remaining <= 0) {
            long over = -remaining;
            long overMinute = over / MINUTE;
            if (over >= AUTO_END_OVERTIME) {
                finish(true, false);
                return;
            }
            if (alarmRinging && over >= ALARM_MAX_OVERTIME) stopAlarm();
            if (overMinute >= 1 && overMinute > lastOverMinute && over < VOICE_MAX_OVERTIME) {
                lastOverMinute = overMinute;
                stopAlarm();
                handler.removeCallbacks(resumeAlarm);
                alert(3, (overMinute == 1 ? "One minute" : overMinute + " minutes") + " over. Time to stop.");
                if (over < ALARM_MAX_OVERTIME) handler.postDelayed(resumeAlarm, 4500);
            }
        }
    }

    private void finish(boolean record, boolean confirmed) {
        if (!running) {
            stopSelf();
            return;
        }
        running = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(resumeAlarm);
        stopAlarm();
        if (tts != null) tts.stop();
        if (savedAlarmVolume >= 0) {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0);
            savedAlarmVolume = -1;
        }
        if (record) {
            long actual = SystemClock.elapsedRealtime() - startElapsed;
            Store.Run run = new Store.Run(startWall, label, endElapsed - startElapsed, actual, confirmed);
            Store.addRun(this, run);
            lastRun = run;
        } else {
            lastRun = null;
        }
        if (wakeLock.isHeld()) wakeLock.release();
        stopForeground(true);
        stopSelf();
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
            vibrator.vibrate(pattern, -1);
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                speak(text);
            }
        }, urgency * 350L + 200);
    }

    private void speak(String text) {
        if (!ttsReady) return;
        Bundle params = new Bundle();
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "timenudge");
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
            alarm.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
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

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, TimerActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        String title = label.isEmpty() ? "Timer" : label;
        return new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText("Countdown running. Tap to open.")
                .setOngoing(true)
                .setContentIntent(open)
                .build();
    }
}
