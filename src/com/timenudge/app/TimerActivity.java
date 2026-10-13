package com.timenudge.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;

/** Full-screen countdown. Keeps the screen on and shows over the lock screen. */
public class TimerActivity extends Activity {
    private final Handler handler = new Handler();
    private LinearLayout root;
    private TextView labelView, leaveBy, clock, status;
    private WedgeView wedge;
    private Button outButton, silenceButton, cancelButton;
    private boolean showingSummary;
    private long openedAt;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 200);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        openedAt = SystemClock.elapsedRealtime();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        int pad = Ui.dp(this, 20);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Ui.BG);

        labelView = Ui.text(this, "", 24, Ui.TEXT);
        labelView.setGravity(Gravity.CENTER);
        root.addView(labelView);

        leaveBy = Ui.text(this, "", 18, Ui.MUTED);
        leaveBy.setGravity(Gravity.CENTER);
        root.addView(leaveBy);

        wedge = new WedgeView(this);
        root.addView(wedge, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        clock = Ui.text(this, "", 84, Ui.TEXT);
        clock.setGravity(Gravity.CENTER);
        clock.setIncludeFontPadding(false);
        root.addView(clock);

        status = Ui.text(this, "", 20, Ui.MUTED);
        status.setGravity(Gravity.CENTER);
        root.addView(status);

        outButton = Ui.button(this, "Done ✓", Ui.GREEN, 26);
        LinearLayout.LayoutParams big = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 84));
        big.topMargin = Ui.dp(this, 16);
        root.addView(outButton, big);
        outButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (showingSummary) {
                    goHome();
                } else {
                    TimerService.send(TimerActivity.this, TimerService.ACTION_DONE);
                }
            }
        });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 60));
        rowLp.topMargin = Ui.dp(this, 10);
        root.addView(row, rowLp);

        silenceButton = Ui.button(this, "Silence alarm", Ui.RED, 18);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        half.rightMargin = Ui.dp(this, 10);
        row.addView(silenceButton, half);
        silenceButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TimerService.send(TimerActivity.this, TimerService.ACTION_SILENCE);
            }
        });

        cancelButton = Ui.button(this, "Cancel", Ui.CARD, 18);
        row.addView(cancelButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(TimerActivity.this)
                        .setMessage("Cancel this timer? It won't be logged.")
                        .setPositiveButton("Cancel timer", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                TimerService.send(TimerActivity.this, TimerService.ACTION_CANCEL);
                                goHome();
                            }
                        })
                        .setNegativeButton("Keep going", null)
                        .show();
            }
        });

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!TimerService.running && Store.activeTimer(this) != null) {
            TimerService.send(this, TimerService.ACTION_WAKE);
        }
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
    }

    private void goHome() {
        TimerService.lastRun = null;
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void render() {
        if (!TimerService.running) {
            Store.Run run = TimerService.lastRun;
            if (run == null) {
                // Service hasn't started yet (just launched) or the timer was cancelled.
                if (SystemClock.elapsedRealtime() - openedAt > 2000) goHome();
                return;
            }
            renderSummary(run);
            return;
        }
        showingSummary = false;
        long now = SystemClock.elapsedRealtime();
        long total = Math.max(1, TimerService.endElapsed - TimerService.startElapsed);
        long remaining = TimerService.endElapsed - now;
        float progress = (now - TimerService.startElapsed) / (float) total;

        String label = TimerService.label;
        labelView.setText(label.isEmpty() ? "Timer" : label);
        long leaveWall = TimerService.startWall + total;
        leaveBy.setText("Ends at " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(leaveWall)));

        if (remaining > 0) {
            int color = Ui.colorFor(progress);
            root.setBackgroundColor(Ui.BG);
            wedge.set(remaining / (float) total, color, false, false);
            clock.setText(Ui.clock(remaining, true));
            clock.setTextColor(color);
            status.setText(remaining <= 60000 ? "Finish up" : "left");
        } else {
            boolean flash = ((-remaining) / 500) % 2 == 0;
            root.setBackgroundColor(flash ? Ui.blend(Ui.BG, Ui.RED, 0.45f) : Ui.BG);
            wedge.set(0f, Ui.RED, true, flash);
            clock.setText("+" + Ui.clock(-remaining, false));
            clock.setTextColor(Ui.RED);
            status.setText("Over time. Wrap it up!");
        }
        outButton.setText("Done ✓");
        silenceButton.setVisibility(TimerService.alarmRinging ? View.VISIBLE : View.INVISIBLE);
        cancelButton.setVisibility(View.VISIBLE);
    }

    private void renderSummary(Store.Run run) {
        showingSummary = true;
        root.setBackgroundColor(Ui.BG);
        long over = run.overMs();
        int color = over <= 0 ? Ui.GREEN : (over < 3 * 60000 ? Ui.AMBER : Ui.RED);
        wedge.set(over <= 0 ? 1f : 0f, color, over > 0, false);
        labelView.setText(run.label.isEmpty() ? "Done" : run.label);
        leaveBy.setText("Planned " + Ui.clock(run.plannedMs, false) + ", took " + Ui.clock(run.actualMs, false));
        clock.setText(Ui.signed(over));
        clock.setTextColor(color);
        status.setText(over <= 0 ? "On time. Nice!" : (run.confirmed ? "over your plan" : "over (auto-ended, nobody tapped Done)"));
        outButton.setText("Done");
        silenceButton.setVisibility(View.INVISIBLE);
        cancelButton.setVisibility(View.INVISIBLE);
    }
}
