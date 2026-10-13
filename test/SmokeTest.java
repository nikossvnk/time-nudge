package com.timenudge.app;

import static org.junit.Assert.*;

import android.content.Intent;
import android.speech.tts.TextToSpeech;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowTextToSpeech;
import org.robolectric.shadow.api.Shadow;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 26, manifest = Config.NONE)
public class SmokeTest {
    private static String spoken(TimerService s) throws Exception {
        java.lang.reflect.Field f = TimerService.class.getDeclaredField("tts");
        f.setAccessible(true);
        ShadowTextToSpeech sh = Shadow.extract(f.get(s));
        return sh.getLastSpokenText();
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
}
