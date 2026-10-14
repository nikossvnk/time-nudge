package io.github.nikossvnk.timenudge;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;

/** Small helpers shared by the screens (layouts are built in code). */
final class Ui {
    static final int BG = Color.rgb(18, 18, 18);
    static final int CARD = Color.rgb(38, 38, 38);
    static final int TEXT = Color.rgb(240, 240, 240);
    static final int MUTED = Color.rgb(160, 160, 160);
    static final int GREEN = Color.rgb(67, 160, 71);
    static final int AMBER = Color.rgb(249, 168, 37);
    static final int RED = Color.rgb(229, 57, 53);
    static final int ACCENT = Color.rgb(255, 97, 40);  // the logo's orange, #FF6128
    // The icon ring's gradient, yellow to orange to red (assets/icon.svg).
    static final int RING_YELLOW = Color.rgb(255, 189, 32);
    static final int RING_ORANGE = Color.rgb(255, 89, 37);
    static final int RING_RED = Color.rgb(255, 52, 56);

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    static Button button(Context c, String s, int bg, float sp) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(c, 14));
        b.setBackground(d);
        int p = dp(c, 12);
        b.setPadding(p, p, p, p);
        b.setStateListAnimator(null);
        return b;
    }

    /** The icon ring's colours: yellow for the first half, turning orange, then red at the end. */
    static int colorFor(float progress) {
        if (progress < 0.5f) return RING_YELLOW;
        if (progress < 0.8f) return blend(RING_YELLOW, RING_ORANGE, (progress - 0.5f) / 0.3f);
        return blend(RING_ORANGE, RING_RED, Math.min(1f, (progress - 0.8f) / 0.2f));
    }

    static int blend(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** "9:05", or "1:02:03" past an hour. Rounds up so the display hits 0:00 exactly at the end. */
    static String clock(long ms, boolean roundUp) {
        long s = roundUp ? (ms + 999) / 1000 : ms / 1000;
        if (s < 0) s = 0;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, sec);
        return String.format("%d:%02d", m, sec);
    }

    /** "+4:30" / "-1:10" style difference. */
    static String signed(long ms) {
        return (ms >= 0 ? "+" : "−") + clock(Math.abs(ms), false);
    }
}
