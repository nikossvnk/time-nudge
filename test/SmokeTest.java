package com.getgoing.app;

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

    @Test
    public void fullRun() throws Exception {
        ActivityController<MainActivity> main = Robolectric.buildActivity(MainActivity.class).setup();
        String home = allText(main.get().getWindow().getDecorView());
        System.out.println("HOME: " + home);
        assertTrue(home.contains("Leave for work"));

        TimerService svc = Robolectric.buildService(TimerService.class).create().get();
        svc.onInit(TextToSpeech.SUCCESS);
        svc.onStartCommand(new Intent(TimerService.ACTION_START).putExtra(TimerService.EXTRA_MS, 10 * 60000L)
                .putExtra(TimerService.EXTRA_LABEL, "Leave for work"), 0, 1);
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
        assertEquals("One minute late. Go now!", spoken(svc));

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
