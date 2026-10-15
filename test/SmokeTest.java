package io.github.nikossvnk.timenudge;

import static org.junit.Assert.*;

import android.content.Intent;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowTextToSpeech;
import org.robolectric.shadow.api.Shadow;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {25, 37}, manifest = Config.NONE)  // the old phone, and Android 17
public class SmokeTest {
    private static String spoken(TimerService s) throws Exception {
        java.lang.reflect.Field f = TimerService.class.getDeclaredField("tts");
        f.setAccessible(true);
        ShadowTextToSpeech sh = Shadow.extract(f.get(s));
        return sh.getLastSpokenText();
    }

    @Before
    public void resetTimerState() {
        TimerService.running = false;
        TimerService.alarmRinging = false;
        TimerService.lastRun = null;
    }

    private static TimerService newService() {
        TimerService svc = Robolectric.buildService(TimerService.class).create().get();
        svc.onInit(TextToSpeech.SUCCESS);
        return svc;
    }

    private static void startTimer(TimerService svc, long ms, String label) {
        svc.onStartCommand(new Intent(TimerService.ACTION_START).putExtra(TimerService.EXTRA_MS, ms)
                .putExtra(TimerService.EXTRA_LABEL, label), 0, 1);
    }

    /** What Android killing the process leaves behind: no statics, no pending callbacks. */
    private static void killProcess(TimerService svc) throws Exception {
        java.lang.reflect.Field f = TimerService.class.getDeclaredField("handler");
        f.setAccessible(true);
        ((android.os.Handler) f.get(svc)).removeCallbacksAndMessages(null);
        resetStatics();
    }

    private static void resetStatics() {
        TimerService.running = false;
        TimerService.alarmRinging = false;
        TimerService.lastRun = null;
        TimerService.label = "";
        TimerService.startElapsed = TimerService.endElapsed = TimerService.startWall = 0;
    }

    /** Shadow.extract rather than Shadows.shadowOf, which needs many more imports. */
    private static org.robolectric.shadows.ShadowService shadowService(TimerService svc) {
        return Shadow.extract(svc);
    }

    private static Intent savedIntent(android.app.PendingIntent pi) {
        org.robolectric.shadows.ShadowPendingIntent sh = Shadow.extract(pi);
        return sh.getSavedIntent();
    }

    private static android.media.AudioManager audio() {
        return (android.media.AudioManager) RuntimeEnvironment.application.getSystemService(android.content.Context.AUDIO_SERVICE);
    }

    private static android.view.View find(android.view.View v, String prefix) {
        if (v instanceof TextView && ((TextView) v).getText().toString().startsWith(prefix)) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View found = find(g.getChildAt(i), prefix);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void advance(long ms) {
        ShadowLooper.idleMainLooper(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static String allText(android.view.View v) {
        StringBuilder sb = new StringBuilder();
        if (v instanceof TextView) sb.append(((TextView) v).getText()).append(" | ");
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) sb.append(allText(g.getChildAt(i)));
        }
        return sb.toString();
    }

    /** Starts a timer and records what is spoken, as "minute text", second by second until untilMs. */
    private static java.util.List<String> speechTimeline(long timerMs, long untilMs) throws Exception {
        TimerService svc = Robolectric.buildService(TimerService.class).create().get();
        svc.onInit(TextToSpeech.SUCCESS);
        svc.onStartCommand(new Intent(TimerService.ACTION_START).putExtra(TimerService.EXTRA_MS, timerMs)
                .putExtra(TimerService.EXTRA_LABEL, ""), 0, 1);
        java.util.List<String> out = new java.util.ArrayList<String>();
        String last = null;
        for (long t = 1000; t <= untilMs && TimerService.running; t += 1000) {
            advance(1000);
            String now = spoken(svc);
            if (now != null && !now.equals(last)) {
                out.add((t / 60000) + " " + now);
                last = now;
            }
        }
        svc.onStartCommand(new Intent(TimerService.ACTION_CANCEL), 0, 2);
        return out;
    }

    /** Minutes elapsed (rounded down) of each spoken line after the "Timer started" one. */
    private static java.util.List<Long> minutesOf(java.util.List<String> timeline) {
        java.util.List<Long> out = new java.util.ArrayList<Long>();
        for (String line : timeline.subList(1, timeline.size())) out.add(Long.parseLong(line.split(" ")[0]));
        return out;
    }

    @Test
    public void longCountdownSpeaksAtLeastEveryFiveMinutes() throws Exception {
        java.util.List<String> timeline = speechTimeline(40 * 60000L, 40 * 60000L - 1000);
        System.out.println("40 MIN: " + timeline);
        assertEquals("0 Timer started. 40 minutes.", timeline.get(0));
        assertEquals("5 35 minutes left.", timeline.get(1));
        assertTrue(timeline.contains("20 20 minutes left."));
        assertTrue(timeline.contains("35 5 minutes left."));
        assertTrue(timeline.contains("38 2 minutes left. Wrap it up."));
        assertEquals("39 30 seconds.", timeline.get(timeline.size() - 1));
        long prev = 0;
        for (long m : minutesOf(timeline)) {
            assertTrue("gap before minute " + m, m - prev <= 5);
            prev = m;
        }
    }

    @Test
    public void checkpointsNeverMoreThanFiveMinutesApart() {
        for (long min = 1; min <= 180; min++) {
            long total = min * 60000L, prev = total;
            for (long cp : TimerService.checkpointsFor(total)) {
                assertTrue(min + " min timer: gap above " + cp / 1000 + "s left", prev - cp <= 5 * 60000L);
                prev = cp;
            }
            assertTrue(min + " min timer: gap before the end", prev <= 5 * 60000L);
        }
        // The short-timer schedule is unchanged: 10 minutes announces at 7, 4, 2, 1 min and 30 s left.
        assertEquals(java.util.Arrays.asList(420000L, 240000L, 120000L, 60000L, 30000L),
                TimerService.checkpointsFor(10 * 60000L));
    }

    @Test
    public void overtimeSpeaksEveryMinuteThenEveryFiveUntilAutoEnd() throws Exception {
        java.util.List<String> timeline = speechTimeline(5 * 60000L, 40 * 60000L);
        System.out.println("OVERTIME: " + timeline);
        java.util.List<String> over = new java.util.ArrayList<String>();
        for (String line : timeline) if (line.contains("over.") || line.contains("Time's up")) over.add(line);
        assertEquals(java.util.Arrays.asList(
                "5 Time's up!",
                "6 One minute over. Time to stop.",
                "7 2 minutes over. Time to stop.",
                "8 3 minutes over. Time to stop.",
                "9 4 minutes over. Time to stop.",
                "10 5 minutes over. Time to stop.",
                "11 6 minutes over. Time to stop.",
                "12 7 minutes over. Time to stop.",
                "13 8 minutes over. Time to stop.",
                "14 9 minutes over. Time to stop.",
                "15 10 minutes over. Time to stop.",
                "20 15 minutes over. Time to stop.",
                "25 20 minutes over. Time to stop.",
                "30 25 minutes over. Time to stop."), over);
        // Auto-ended and logged as unconfirmed at 30 minutes over.
        assertFalse(TimerService.running);
        assertNotNull(TimerService.lastRun);
        assertFalse(TimerService.lastRun.confirmed);
        assertEquals(35 * 60000L, TimerService.lastRun.actualMs, 1000);
    }

    @Test
    public void startingANewTimerWhileOneRunsKeepsTheServiceAlive() throws Exception {
        TimerService svc = newService();
        startTimer(svc, 10 * 60000L, "First");
        advance(1000);
        startTimer(svc, 5 * 60000L, "Second");
        advance(1000);
        assertTrue(TimerService.running);
        assertEquals("Second", TimerService.label);
        assertEquals("Timer started. 5 minutes.", spoken(svc));
        assertFalse("service stopped itself", shadowService(svc).isStoppedBySelf());
        assertNull("the replaced timer isn't logged", TimerService.lastRun);
        assertTrue(Store.history(svc).isEmpty());
    }

    @Test
    public void continuesAfterTheProcessIsKilledAndRestoresTheVolume() throws Exception {
        audio().setStreamVolume(android.media.AudioManager.STREAM_ALARM, 2, 0);
        TimerService svc = newService();
        startTimer(svc, 10 * 60000L, "Get ready to leave");
        long end = TimerService.endElapsed;
        advance(3 * 60000L + 2000);
        assertEquals("7 minutes left.", spoken(svc));
        assertTrue(audio().getStreamVolume(android.media.AudioManager.STREAM_ALARM) > 2);

        killProcess(svc);
        advance(60000L);
        TimerService again = newService();
        int mode = again.onStartCommand(null, 0, 1);  // Android restarting a sticky service
        assertEquals(android.app.Service.START_STICKY, mode);
        assertTrue(TimerService.running);
        assertEquals("Get ready to leave", TimerService.label);
        assertEquals(end, TimerService.endElapsed);
        advance(2000);
        assertNull("nothing re-announced on restore", spoken(again));

        advance(2 * 60000L);  // now 6:04 in
        assertEquals("4 minutes left.", spoken(again));

        again.onStartCommand(new Intent(TimerService.ACTION_DONE), 0, 2);
        assertFalse(TimerService.running);
        assertEquals(2, audio().getStreamVolume(android.media.AudioManager.STREAM_ALARM));
        Store.Run run = Store.history(again).get(0);
        assertEquals(10 * 60000L, run.plannedMs);
        assertEquals(6 * 60000L + 4000, run.actualMs, 1000);
        assertTrue(run.confirmed);
        assertNull(Store.activeTimer(again));
    }

    @Test
    public void backupAlarmWakesAKilledTimerWhenTimeIsUp() throws Exception {
        TimerService svc = newService();
        startTimer(svc, 5 * 60000L, "");
        advance(4 * 60000L + 33000);  // speech starts after the beeps
        assertEquals("30 seconds.", spoken(svc));
        org.robolectric.shadows.ShadowAlarmManager alarms = Shadow.extract(
                RuntimeEnvironment.application.getSystemService(android.content.Context.ALARM_SERVICE));
        org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm next = alarms.getNextScheduledAlarm();
        assertEquals(TimerService.endElapsed, next.triggerAtTime);
        Intent wake = savedIntent(next.operation);
        assertEquals(TimerService.ACTION_WAKE, wake.getAction());

        killProcess(svc);
        advance(30000);  // the alarm goes off just after the end
        TimerService again = newService();
        again.onStartCommand(wake, 0, 1);
        advance(3000);
        assertTrue(TimerService.running);
        assertEquals("Time's up!", spoken(again));
    }

    @Test
    public void notificationCountsDownAndHasAWorkingDoneButton() throws Exception {
        TimerService svc = newService();
        startTimer(svc, 10 * 60000L, "Get ready to leave");
        android.app.Notification n = shadowService(svc).getLastForegroundNotification();
        assertEquals("Get ready to leave", n.extras.getString(android.app.Notification.EXTRA_TITLE));
        assertTrue(n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().startsWith("Ends at "));
        assertEquals(TimerService.startWall + 10 * 60000L, n.when);
        assertTrue(n.extras.getBoolean(android.app.Notification.EXTRA_CHRONOMETER_COUNT_DOWN));
        assertEquals(1, n.actions.length);
        assertEquals("Done", n.actions[0].title.toString());

        advance(10 * 60000L + 2000);
        android.app.NotificationManager nm = (android.app.NotificationManager)
                RuntimeEnvironment.application.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
        org.robolectric.shadows.ShadowNotificationManager shadowNm = Shadow.extract(nm);
        android.app.Notification over = shadowNm.getNotification(1);
        assertTrue(over.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().startsWith("Over time since "));
        assertFalse(over.extras.getBoolean(android.app.Notification.EXTRA_CHRONOMETER_COUNT_DOWN));

        // Tapping Done in the notification ends and logs the run.
        svc.onStartCommand(savedIntent(over.actions[0].actionIntent), 0, 2);
        assertFalse(TimerService.running);
        Store.Run run = Store.history(svc).get(0);
        assertTrue(run.confirmed);
        assertEquals(10 * 60000L + 2000, run.actualMs, 1000);
    }

    @Test
    public void statsIgnoreAutoEndedRunsAndPresetsShowTheirUsualTime() throws Exception {
        android.content.Context c = RuntimeEnvironment.application;
        Store.presets(c);  // creates the defaults, including "Quick task" (10 min)
        long[] actualMin = {12, 13, 14};
        for (long m : actualMin) Store.addRun(c, new Store.Run(1000L, "Quick task", 10 * 60000L, m * 60000L, true));
        Store.addRun(c, new Store.Run(2000L, "Quick task", 10 * 60000L, 40 * 60000L, false));

        ActivityController<MainActivity> main = Robolectric.buildActivity(MainActivity.class).setup();
        String home = allText(main.get().getWindow().getDecorView());
        System.out.println("HOME STATS: " + home);
        assertTrue(home.contains("Last 3 runs: 0 on time. On average 3:00 over your plan."));
        assertTrue(home.contains("Runs marked ? ended on their own and don't count."));
        assertTrue(home.contains("Quick task  ·  10 min\nusually 13 min"));

        find(main.get().getWindow().getDecorView(), "Quick task  ·  10 min").performLongClick();
        android.app.AlertDialog dialog = (android.app.AlertDialog) org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        android.widget.Button use = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
        assertEquals("Use 13 min", use.getText().toString());
        use.performClick();
        ShadowLooper.idleMainLooper();  // dialog buttons reach their listener through the message queue
        boolean found = false;
        for (Store.Preset p : Store.presets(c)) {
            if (p.name.equals("Quick task")) {
                assertEquals(13, p.minutes);
                found = true;
            }
        }
        assertTrue(found);
        assertTrue(allText(main.get().getWindow().getDecorView()).contains("Quick task  ·  13 min"));
    }

    private static void swipe(android.view.View v, float fromX, float toX) {
        long t = SystemClock.uptimeMillis();
        v.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, fromX, 20, 0));
        for (int i = 1; i <= 5; i++) {
            float x = fromX + (toX - fromX) * i / 5;
            v.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t + i * 16, android.view.MotionEvent.ACTION_MOVE, x, 20, 0));
        }
        v.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t + 100, android.view.MotionEvent.ACTION_UP, toX, 20, 0));
        advance(500);
    }

    private static boolean hasPreset(String name) {
        for (Store.Preset p : Store.presets(RuntimeEnvironment.application)) if (p.name.equals(name)) return true;
        return false;
    }

    @Test
    public void swipingAPresetLeftDeletesItButAShortSwipeOrLongPressDoesNot() throws Exception {
        ActivityController<MainActivity> main = Robolectric.buildActivity(MainActivity.class).setup();
        android.view.View root = main.get().getWindow().getDecorView();
        assertTrue(allText(root).contains("Swipe a preset left to delete it."));

        android.view.View focus = find(root, "Focus block");
        focus.performLongClick();
        assertNull("no stats, so no long-press dialog", org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
        android.view.View behind = ((android.view.ViewGroup) focus.getParent()).getChildAt(0);
        assertEquals("Delete", ((TextView) behind).getText().toString());
        assertEquals("red layer hidden until a swipe", 0f, behind.getAlpha(), 0.01f);
        swipe(focus, 400, 350);  // too short: springs back
        assertTrue(hasPreset("Focus block"));
        assertEquals(0f, focus.getTranslationX(), 0.01f);
        assertEquals("hidden again after springing back", 0f, behind.getAlpha(), 0.01f);
        swipe(focus, 400, 450);  // to the right: ignored
        assertTrue(hasPreset("Focus block"));

        swipe(focus, 400, 50);
        assertFalse(hasPreset("Focus block"));
        assertTrue(hasPreset("Quick task"));
        assertFalse(allText(root).contains("Focus block  ·"));
        assertFalse("a swipe isn't a tap", TimerService.running);
    }

    private static java.util.List<String> presetNames() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (Store.Preset p : Store.presets(RuntimeEnvironment.application)) out.add(p.name + " " + p.minutes);
        return out;
    }

    @Test
    public void undoPutsASwipedAwayPresetBackInItsPlace() throws Exception {
        ActivityController<MainActivity> main = Robolectric.buildActivity(MainActivity.class).setup();
        android.view.View root = main.get().getWindow().getDecorView();
        java.util.List<String> before = presetNames();
        assertEquals(java.util.Arrays.asList("Get ready to leave 15", "Quick task 10", "Focus block 25"), before);

        swipe(find(root, "Quick task"), 400, 50);  // the middle one
        assertEquals(java.util.Arrays.asList("Get ready to leave 15", "Focus block 25"), presetNames());
        assertTrue(allText(root).contains("Deleted \"Quick task\" | Undo"));
        ((android.view.View) find(root, "Deleted \"Quick task\"").getParent()).performClick();
        assertEquals(before, presetNames());
        assertTrue(allText(root).contains("Quick task  ·  10 min"));
        assertFalse(allText(root).contains("Undo"));

        // Without Undo the bar goes away after a while and the preset stays deleted.
        swipe(find(root, "Focus block"), 400, 50);  // the last one
        assertTrue(allText(root).contains("Deleted \"Focus block\" | Undo"));
        advance(9000);
        assertFalse(allText(root).contains("Undo"));
        assertEquals(java.util.Arrays.asList("Get ready to leave 15", "Quick task 10"), presetNames());
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) n++;
        return n;
    }

    @Test
    public void historyShowsFiveRunsByDayUntilShowAllIsTapped() throws Exception {
        android.content.Context c = RuntimeEnvironment.application;
        long now = System.currentTimeMillis();
        // 30 runs, 4 hours apart going back from now (oldest added first; history is newest first).
        for (int i = 29; i >= 0; i--) {
            Store.addRun(c, new Store.Run(now - i * 4 * 3600000L, "Run " + i, 10 * 60000L, 11 * 60000L, true));
        }
        ActivityController<MainActivity> main = Robolectric.buildActivity(MainActivity.class).setup();
        android.view.View root = main.get().getWindow().getDecorView();
        String folded = allText(root);
        System.out.println("HISTORY FOLDED: " + folded.substring(folded.indexOf("Your track record")));
        assertEquals(5, count(folded, "planned 10:00"));
        assertTrue(folded.contains("Today"));
        assertTrue(folded.contains("Run 0\nplanned 10:00"));
        assertFalse(folded.contains("Run 5\n"));

        find(root, "Show all 30 runs").performClick();
        String all = allText(root);
        assertEquals(30, count(all, "planned 10:00"));
        assertTrue(all.contains("Yesterday"));
        assertTrue(all.contains("Run 29\n"));
        assertTrue(all.contains("Show fewer"));

        find(root, "Show fewer").performClick();
        assertEquals(5, count(allText(root), "planned 10:00"));
    }

    @Test
    public void fullRun() throws Exception {
        ActivityController<MainActivity> main = Robolectric.buildActivity(MainActivity.class).setup();
        String home = allText(main.get().getWindow().getDecorView());
        System.out.println("HOME: " + home);
        assertTrue(home.contains("Get ready to leave"));

        TimerService svc = Robolectric.buildService(TimerService.class).create().get();
        svc.onInit(TextToSpeech.SUCCESS);
        svc.onStartCommand(new Intent(TimerService.ACTION_START).putExtra(TimerService.EXTRA_MS, 10 * 60000L)
                .putExtra(TimerService.EXTRA_LABEL, "Get ready to leave"), 0, 1);
        advance(1000);
        System.out.println("t=0: " + spoken(svc));
        assertTrue(TimerService.running);

        ActivityController<TimerActivity> timer = Robolectric.buildActivity(TimerActivity.class).setup();
        advance(500);
        System.out.println("TIMER: " + allText(timer.get().getWindow().getDecorView()));

        long[] marks = {3 * 60000 + 2000, 6 * 60000 + 2000, 8 * 60000 + 2000, 9 * 60000 + 2000, 9 * 60000 + 32000, 10 * 60000 + 5000, 11 * 60000 + 6000};
        long t = 1500;
        for (long m : marks) {
            advance(m - t);
            t = m;
            System.out.println("t=" + m / 1000 + "s spoken=" + spoken(svc) + " ringing=" + TimerService.alarmRinging);
        }
        System.out.println("TIMER LATE: " + allText(timer.get().getWindow().getDecorView()));
        assertEquals("One minute over. Time to stop.", spoken(svc));

        svc.onStartCommand(new Intent(TimerService.ACTION_DONE), 0, 2);
        assertFalse(TimerService.running);
        assertNotNull(TimerService.lastRun);
        advance(500);
        System.out.println("SUMMARY: " + allText(timer.get().getWindow().getDecorView()));

        main.pause().resume();
        System.out.println("HOME AFTER: " + allText(main.get().getWindow().getDecorView()));
        assertEquals(1, Store.history(main.get()).size());
    }

    // --- what changes between Android 7.1 and Android 17 ------------------------------------

    private static org.robolectric.shadows.ShadowAlarmManager alarms() {
        return Shadow.extract(RuntimeEnvironment.getApplication().getSystemService(android.content.Context.ALARM_SERVICE));
    }

    @Test
    public void timerRunsAsAnAlarmTypeForegroundServiceWithItsOwnChannel() throws Exception {
        TimerService svc = newService();
        startTimer(svc, 10 * 60000L, "Get ready to leave");
        android.app.Notification n = shadowService(svc).getLastForegroundNotification();
        assertNotNull("the service is in the foreground", n);
        assertEquals("Get ready to leave", n.extras.getString(android.app.Notification.EXTRA_TITLE));
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            assertEquals("timer", n.getChannelId());
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    RuntimeEnvironment.getApplication().getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            android.app.NotificationChannel channel = nm.getNotificationChannel("timer");
            assertEquals("Running timer", channel.getName().toString());
            assertEquals("silent: the app makes its own sounds",
                    android.app.NotificationManager.IMPORTANCE_LOW, channel.getImportance());
        }
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                    svc.getForegroundServiceType());
        }
        // Both buttons of the notification keep working with immutable PendingIntents.
        org.robolectric.shadows.ShadowPendingIntent open = Shadow.extract(n.contentIntent);
        assertTrue(open.isImmutable());
        assertEquals(TimerActivity.class.getName(), open.getSavedIntent().getComponent().getClassName());
        org.robolectric.shadows.ShadowPendingIntent done = Shadow.extract(n.actions[0].actionIntent);
        assertTrue(done.isImmutable());
        assertEquals(TimerService.ACTION_DONE, done.getSavedIntent().getAction());
    }

    @Test
    public void backupAlarmIsExactAndStartsAForegroundServiceOnAndroid8AndUp() throws Exception {
        // Installing the app grants USE_EXACT_ALARM; the tests have no manifest, so grant it here.
        if (android.os.Build.VERSION.SDK_INT >= 31) org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true);
        TimerService svc = newService();
        startTimer(svc, 10 * 60000L, "");
        org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm next = alarms().getNextScheduledAlarm();
        assertEquals(TimerService.endElapsed - 7 * 60000L, next.getTriggerAtMs());
        assertEquals(org.robolectric.shadows.ShadowAlarmManager.WINDOW_EXACT, next.getWindowLengthMs());
        assertTrue(next.isAllowWhileIdle());
        org.robolectric.shadows.ShadowPendingIntent wake = Shadow.extract(next.operation);
        assertTrue(wake.isImmutable());
        assertEquals(TimerService.ACTION_WAKE, wake.getSavedIntent().getAction());
        // Android 8+ doesn't let an alarm start a plain service of an app in the background.
        assertEquals(android.os.Build.VERSION.SDK_INT >= 26, wake.isForegroundServiceIntent());
    }

    @Test
    @Config(sdk = 37)
    public void withoutTheExactAlarmPermissionTheBackupAlarmIsInexact() throws Exception {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false);
        TimerService svc = newService();
        startTimer(svc, 10 * 60000L, "");
        org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm next = alarms().getNextScheduledAlarm();
        assertNotNull("still an alarm, just not an exact one", next);
        assertEquals(TimerService.endElapsed - 7 * 60000L, next.getTriggerAtMs());
        assertNotEquals(org.robolectric.shadows.ShadowAlarmManager.WINDOW_EXACT, next.getWindowLengthMs());
        assertTrue(next.isAllowWhileIdle());
        advance(3 * 60000L + 2000);
        assertEquals("the countdown itself doesn't depend on the alarm", "7 minutes left.", spoken(svc));
    }

    @Test
    public void aWakeAlarmWithNothingToContinueStopsWithoutCrashing() throws Exception {
        TimerService svc = newService();
        int mode = svc.onStartCommand(new Intent(TimerService.ACTION_WAKE), 0, 1);
        assertEquals(android.app.Service.START_NOT_STICKY, mode);
        assertFalse(TimerService.running);
        assertTrue(shadowService(svc).isStoppedBySelf());
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            // Started as a foreground service, it has to go to the foreground before stopping.
            assertEquals(1, shadowService(svc).getLastForegroundNotificationId());
            assertTrue(shadowService(svc).isForegroundStopped());
        }
        android.app.NotificationManager nm = (android.app.NotificationManager)
                RuntimeEnvironment.getApplication().getSystemService(android.content.Context.NOTIFICATION_SERVICE);
        org.robolectric.shadows.ShadowNotificationManager shadowNm = Shadow.extract(nm);
        assertEquals("no notification left behind", 0, shadowNm.size());
    }

    @Test
    public void ifAndroidRefusesTheForegroundTheTimerStillRunsAndShowsItsNotification() throws Exception {
        TimerService svc = Robolectric.buildService(TimerService.class).create().get();
        shadowService(svc).setThrowInStartForeground(new IllegalStateException("not allowed from the background"));
        svc.onInit(TextToSpeech.SUCCESS);
        startTimer(svc, 5 * 60000L, "Quick task");
        assertTrue(TimerService.running);
        android.app.NotificationManager nm = (android.app.NotificationManager)
                RuntimeEnvironment.getApplication().getSystemService(android.content.Context.NOTIFICATION_SERVICE);
        org.robolectric.shadows.ShadowNotificationManager shadowNm = Shadow.extract(nm);
        assertEquals("Quick task", shadowNm.getNotification(1).extras.getString(android.app.Notification.EXTRA_TITLE));
        advance(5 * 60000L + 4000);
        assertEquals("Time's up!", spoken(svc));
    }

    @Test
    public void asksToShowNotificationsOnlyWhereAndroidRequiresIt() {
        ActivityController<MainActivity> main = Robolectric.buildActivity(MainActivity.class).setup();
        org.robolectric.shadows.ShadowActivity sh = Shadow.extract(main.get());
        org.robolectric.shadows.ShadowActivity.PermissionsRequest asked = sh.getLastRequestedPermission();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            assertArrayEquals(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, asked.requestedPermissions);
        } else {
            assertNull(asked);
        }
    }

    @Test
    public void screensKeepClearOfTheStatusAndNavigationBarsOnAndroid15AndUp() {
        android.view.WindowInsets insets = null;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            insets = new android.view.WindowInsets.Builder()
                    .setInsets(android.view.WindowInsets.Type.statusBars(), android.graphics.Insets.of(0, 63, 0, 0))
                    .setInsets(android.view.WindowInsets.Type.navigationBars(), android.graphics.Insets.of(0, 0, 0, 48))
                    .build();
        }
        ActivityController<MainActivity> main = Robolectric.buildActivity(MainActivity.class).setup();
        android.view.View home = main.get().findViewById(android.R.id.content);
        home = ((android.view.ViewGroup) home).getChildAt(0);  // the ScrollView set as content
        ActivityController<TimerActivity> timer = Robolectric.buildActivity(TimerActivity.class).setup();
        android.view.View countdown = ((android.view.ViewGroup) timer.get().findViewById(android.R.id.content)).getChildAt(0);
        int pad = Ui.dp(timer.get(), 20);
        if (insets != null) {
            home.dispatchApplyWindowInsets(insets);
            countdown.dispatchApplyWindowInsets(insets);
        }
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            assertEquals(63, home.getPaddingTop());
            assertEquals(48, home.getPaddingBottom());
            assertEquals(pad + 63, countdown.getPaddingTop());
            assertEquals(pad + 48, countdown.getPaddingBottom());
        } else {
            assertEquals(0, home.getPaddingTop());
            assertEquals(pad, countdown.getPaddingTop());
        }
    }

    @Test
    public void runsOnTheAndroidVersionsItClaims() {
        // Guards the two-version setup: the class runs once on 7.1 (API 25) and once on Android 17.
        int sdk = android.os.Build.VERSION.SDK_INT;
        assertTrue("SDK " + sdk, sdk == 25 || sdk == 37);
        System.out.println("RAN ON SDK " + sdk);
    }
}
