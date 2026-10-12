package com.getgoing.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** A big shrinking pie showing how much time is left, readable from across the room. */
public class WedgeView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wedge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private float remainingFraction = 1f;
    private boolean overtime;
    private boolean pulse;

    public WedgeView(Context c) {
        super(c);
        track.setColor(Color.argb(40, 255, 255, 255));
        ring.setStyle(Paint.Style.STROKE);
    }

    void set(float remainingFraction, int color, boolean overtime, boolean pulse) {
        this.remainingFraction = Math.max(0f, Math.min(1f, remainingFraction));
        this.overtime = overtime;
        this.pulse = pulse;
        wedge.setColor(color);
        ring.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth(), getHeight()) * 0.92f;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        box.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
        canvas.drawOval(box, track);
        if (overtime) {
            ring.setStrokeWidth(size * (pulse ? 0.12f : 0.05f));
            float inset = ring.getStrokeWidth() / 2;
            box.inset(inset, inset);
            canvas.drawOval(box, ring);
        } else {
            canvas.drawArc(box, -90f, 360f * remainingFraction, true, wedge);
        }
    }
}
