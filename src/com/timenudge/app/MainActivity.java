package com.timenudge.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Home screen: enter minutes, pick a preset, or pick an end time. Shows the track record. */
public class MainActivity extends Activity {
    private static final int[] QUICK = {5, 10, 15, 20, 30};

    private EditText minutesField;
    private Button runningBanner;
    private LinearLayout presetList;
    private LinearLayout historyList;
    private TextView historySummary;
    /** History shows this many runs until "Show all" is tapped. */
    private static final int RUNS_FOLDED = 5;
    private boolean showAllRuns;
    /** How long the Undo bar stays after a preset is swiped away. */
    private static final long UNDO_MS = 8000;
    /** The last swiped-away preset and where it was, while its Undo bar shows. */
    private Store.Preset deletedPreset;
    private int deletedIndex;
    private final Handler handler = new Handler();
    private final Runnable hideUndo = new Runnable() {
        @Override
        public void run() {
            deletedPreset = null;
            renderPresets();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int pad = Ui.dp(this, 20);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);
        scroll.addView(col);

        runningBanner = Ui.button(this, "Timer running. Tap to open", Ui.RED, 20);
        col.addView(runningBanner, fullWidth(64, 0));
        runningBanner.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, TimerActivity.class));
            }
        });

        TextView title = Ui.text(this, "How long do you have?", 26, Ui.TEXT);
        col.addView(title, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 8));

        LinearLayout entry = new LinearLayout(this);
        entry.setOrientation(LinearLayout.HORIZONTAL);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        col.addView(entry, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 8));

        minutesField = new EditText(this);
        minutesField.setInputType(InputType.TYPE_CLASS_NUMBER);
        minutesField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
        minutesField.setTextSize(56);
        minutesField.setTextColor(Ui.TEXT);
        minutesField.setHint("10");
        minutesField.setHintTextColor(Color.rgb(90, 90, 90));
        minutesField.setGravity(Gravity.CENTER);
        entry.addView(minutesField, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        entry.addView(Ui.text(this, "min", 28, Ui.MUTED));

        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        col.addView(quick, fullWidth(56, 8));
        for (int i = 0; i < QUICK.length; i++) {
            final int m = QUICK[i];
            Button b = Ui.button(this, String.valueOf(m), Ui.CARD, 20);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) lp.leftMargin = Ui.dp(this, 8);
            quick.addView(b, lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    minutesField.setText(String.valueOf(m));
                    minutesField.setSelection(minutesField.length());
                }
            });
        }

        Button start = Ui.button(this, "Start", Ui.ACCENT, 28);
        col.addView(start, fullWidth(80, 16));
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Integer m = enteredMinutes();
                if (m != null) startTimer(m * 60000L, "");
            }
        });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        col.addView(row, fullWidth(60, 10));
        Button leaveAt = Ui.button(this, "Until a time…", Ui.CARD, 18);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        half.rightMargin = Ui.dp(this, 10);
        row.addView(leaveAt, half);
        leaveAt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickLeaveTime();
            }
        });
        Button save = Ui.button(this, "Save as preset", Ui.CARD, 18);
        row.addView(save, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                savePreset();
            }
        });

        col.addView(header("Presets"));
        presetList = new LinearLayout(this);
        presetList.setOrientation(LinearLayout.VERTICAL);
        col.addView(presetList);
        col.addView(Ui.text(this, "Swipe a preset left to delete it. Long-press one with a usual time to switch to it.", 14, Ui.MUTED), fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 6));

        col.addView(header("Your track record"));
        historySummary = Ui.text(this, "", 18, Ui.TEXT);
        col.addView(historySummary, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 4));
        historyList = new LinearLayout(this);
        historyList.setOrientation(LinearLayout.VERTICAL);
        col.addView(historyList, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 8));
        TextView clear = Ui.text(this, "Clear history", 15, Ui.MUTED);
        clear.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        col.addView(clear);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage("Delete your whole track record?")
                        .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                Store.clearHistory(MainActivity.this);
                                renderHistory();
                            }
                        })
                        .setNegativeButton("Keep", null)
                        .show();
            }
        });

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean saved = Store.activeTimer(this) != null;
        // A saved timer that isn't running means Android killed the app: bring the timer back.
        if (!TimerService.running && saved) TimerService.send(this, TimerService.ACTION_WAKE);
        runningBanner.setVisibility(TimerService.running || saved ? View.VISIBLE : View.GONE);
        renderPresets();
        renderHistory();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(hideUndo);
        super.onDestroy();
    }

    private LinearLayout.LayoutParams fullWidth(int heightDp, int topMarginDp) {
        int h = heightDp < 0 ? heightDp : Ui.dp(this, heightDp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h);
        lp.topMargin = Ui.dp(this, topMarginDp);
        return lp;
    }

    private TextView header(String s) {
        TextView t = Ui.text(this, s, 20, Ui.ACCENT);
        t.setPadding(0, Ui.dp(this, 28), 0, Ui.dp(this, 8));
        return t;
    }

    private Integer enteredMinutes() {
        String s = minutesField.getText().toString().trim();
        if (s.isEmpty()) s = "10";
        try {
            int m = Integer.parseInt(s);
            if (m >= 1 && m <= 600) return m;
        } catch (NumberFormatException ignored) {
        }
        Toast.makeText(this, "Enter between 1 and 600 minutes", Toast.LENGTH_SHORT).show();
        return null;
    }

    private void startTimer(long ms, String label) {
        hideKeyboard();
        TimerService.start(this, ms, label);
        startActivity(new Intent(this, TimerActivity.class));
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(minutesField.getWindowToken(), 0);
        minutesField.clearFocus();
    }

    private void pickLeaveTime() {
        Calendar now = Calendar.getInstance();
        now.add(Calendar.MINUTE, 15);
        new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            @Override
            public void onTimeSet(TimePicker view, int hour, int minute) {
                Calendar target = Calendar.getInstance();
                target.set(Calendar.HOUR_OF_DAY, hour);
                target.set(Calendar.MINUTE, minute);
                target.set(Calendar.SECOND, 0);
                target.set(Calendar.MILLISECOND, 0);
                long ms = target.getTimeInMillis() - System.currentTimeMillis();
                if (ms <= 0) ms += 24L * 3600 * 1000;
                if (ms < 60000) {
                    Toast.makeText(MainActivity.this, "That's less than a minute away", Toast.LENGTH_SHORT).show();
                    return;
                }
                String when = DateFormat.getTimeFormat(MainActivity.this).format(target.getTime());
                startTimer(ms, "Until " + when);
            }
        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), DateFormat.is24HourFormat(this)).show();
    }

    private void savePreset() {
        final Integer m = enteredMinutes();
        if (m == null) return;
        final EditText name = new EditText(this);
        name.setHint("e.g. Get ready to leave");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        new AlertDialog.Builder(this)
                .setTitle("Save " + m + " min preset")
                .setView(name)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String n = name.getText().toString().trim();
                        if (n.isEmpty()) n = m + " minutes";
                        List<Store.Preset> presets = Store.presets(MainActivity.this);
                        presets.add(new Store.Preset(n, m));
                        Store.savePresets(MainActivity.this, presets);
                        renderPresets();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void renderPresets() {
        presetList.removeAllViews();
        final List<Store.Preset> presets = Store.presets(this);
        List<Store.Run> runs = Store.history(this);
        for (int i = 0; i < presets.size(); i++) {
            if (deletedPreset != null && i == deletedIndex) addUndoBar();
            final Store.Preset p = presets.get(i);
            final int index = i;
            final Integer typical = Store.typicalMinutes(runs, p.name);
            SpannableStringBuilder text = new SpannableStringBuilder(p.name + "  ·  " + p.minutes + " min");
            if (typical != null) {
                int from = text.length();
                text.append("\nusually ").append(String.valueOf(typical)).append(" min");
                text.setSpan(new RelativeSizeSpan(0.7f), from, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(new ForegroundColorSpan(typical > p.minutes ? Ui.AMBER : Ui.MUTED), from,
                        text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            Button b = Ui.button(this, "", Ui.CARD, 20);
            b.setText(text);
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            b.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);

            // Behind the preset: a red "Delete" that shows as it's swiped left.
            FrameLayout row = new FrameLayout(this);
            TextView behind = Ui.button(this, "Delete", Ui.RED, 18);
            behind.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            behind.setPadding(0, 0, Ui.dp(this, 22), 0);
            behind.setClickable(false);
            row.addView(behind, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            row.addView(b, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            presetList.addView(row, fullWidth(typical != null ? 80 : 64, presetList.getChildCount() == 0 ? 0 : 8));

            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startTimer(p.minutes * 60000L, p.name);
                }
            });
            b.setOnTouchListener(new SwipeToDelete(new Runnable() {
                @Override
                public void run() {
                    presets.remove(index);
                    Store.savePresets(MainActivity.this, presets);
                    deletedPreset = p;
                    deletedIndex = index;
                    handler.removeCallbacks(hideUndo);
                    handler.postDelayed(hideUndo, UNDO_MS);
                    renderPresets();
                }
            }));
            if (typical != null && typical != p.minutes) {
                b.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(p.name + "  ·  " + p.minutes + " min")
                                .setMessage("It usually takes you " + typical + " min.")
                                .setPositiveButton("Use " + typical + " min", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        presets.set(index, new Store.Preset(p.name, typical));
                                        Store.savePresets(MainActivity.this, presets);
                                        renderPresets();
                                    }
                                })
                                .setNegativeButton("Keep " + p.minutes + " min", null)
                                .show();
                        return true;
                    }
                });
            }
        }
        if (deletedPreset != null && deletedIndex >= presets.size()) addUndoBar();
    }

    /** "Deleted "X" · Undo" where the preset was; tapping it puts the preset back in its place. */
    private void addUndoBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        GradientDrawable outline = new GradientDrawable();
        outline.setCornerRadius(Ui.dp(this, 14));
        outline.setStroke(Ui.dp(this, 1), Ui.CARD);
        bar.setBackground(outline);
        bar.addView(Ui.text(this, "Deleted \"" + deletedPreset.name + "\"", 16, Ui.MUTED),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView undo = Ui.text(this, "Undo", 18, Ui.ACCENT);
        undo.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(undo);
        bar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<Store.Preset> presets = Store.presets(MainActivity.this);
                presets.add(Math.min(deletedIndex, presets.size()), deletedPreset);
                Store.savePresets(MainActivity.this, presets);
                deletedPreset = null;
                handler.removeCallbacks(hideUndo);
                renderPresets();
            }
        });
        presetList.addView(bar, fullWidth(56, presetList.getChildCount() == 0 ? 0 : 8));
    }

    /**
     * Drag a view left past a threshold to delete it; let go earlier and it slides back. Taps and
     * vertical scrolling pass through untouched.
     */
    private final class SwipeToDelete implements View.OnTouchListener {
        private final Runnable onDelete;
        private final int slop = ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
        private float downX, downY;
        private boolean swiping;

        SwipeToDelete(Runnable onDelete) {
            this.onDelete = onDelete;
        }

        @Override
        public boolean onTouch(final View v, MotionEvent e) {
            float dx = e.getRawX() - downX;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    swiping = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (!swiping && dx < -slop && Math.abs(dx) > Math.abs(e.getRawY() - downY)) {
                        swiping = true;
                        v.getParent().requestDisallowInterceptTouchEvent(true);  // keep the ScrollView out
                        v.cancelLongPress();
                        v.setPressed(false);
                    }
                    if (swiping) v.setTranslationX(Math.min(0, dx));
                    return swiping;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!swiping) return false;
                    swiping = false;
                    // Past 40% of the width (at least 100dp) deletes; anything less springs back.
                    float threshold = Math.max(v.getWidth() * 0.4f, Ui.dp(MainActivity.this, 100));
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && -dx >= threshold) {
                        v.animate().translationX(-Math.max(v.getWidth(), -dx)).setDuration(150);
                        v.postDelayed(onDelete, 160);  // once it has slid out
                    } else {
                        v.animate().translationX(0).setDuration(150);
                    }
                    return true;
                default:
                    return swiping;
            }
        }
    }

    private void renderHistory() {
        historyList.removeAllViews();
        List<Store.Run> runs = Store.history(this);
        if (runs.isEmpty()) {
            historySummary.setText("No runs yet. Your real times will show up here.");
            return;
        }
        historySummary.setText(summary(runs));
        renderRuns(runs);
    }

    /** Stats over the last 10 confirmed runs. Auto-ended ones ("?") would count as 30 min over. */
    private static String summary(List<Store.Run> runs) {
        List<Store.Run> counted = Store.confirmed(runs);
        String note = counted.size() < runs.size() ? "\nRuns marked ? ended on their own and don't count." : "";
        if (counted.isEmpty()) return "No finished runs yet. Tap Done when you finish so the time counts." + note;
        int n = 0, onTime = 0;
        long overSum = 0;
        double ratioSum = 0;
        for (Store.Run r : counted) {
            if (n == 10) break;
            n++;
            if (r.overMs() <= 0) onTime++;
            overSum += r.overMs();
            ratioSum += r.actualMs / (double) Math.max(1, r.plannedMs);
        }
        long avgOver = overSum / n;
        double ratio = ratioSum / n;
        StringBuilder sb = new StringBuilder();
        sb.append("Last ").append(n).append(n == 1 ? " run: " : " runs: ")
                .append(onTime).append(" on time. ");
        if (avgOver > 0) {
            sb.append("On average ").append(Ui.clock(avgOver, false)).append(" over your plan.");
            if (ratio >= 1.15) {
                sb.append(String.format(Locale.US,
                        "\nYou usually need about %.1f× the time you plan, so 10 min really means ~%d.",
                        ratio, Math.round(10 * ratio)));
            }
        } else {
            sb.append("On average ").append(Ui.clock(-avgOver, false)).append(" early. Keep it up!");
        }
        return sb.append(note).toString();
    }

    /** The latest few runs, or all of them, grouped under day headings. */
    private void renderRuns(List<Store.Run> runs) {
        int shown = showAllRuns ? runs.size() : Math.min(runs.size(), RUNS_FOLDED);
        java.text.DateFormat time = DateFormat.getTimeFormat(this);
        String lastDay = null;
        for (int i = 0; i < shown; i++) {
            Store.Run r = runs.get(i);
            String day = dayName(r.startedAt);
            if (!day.equals(lastDay)) {
                TextView heading = Ui.text(this, day, 14, Ui.ACCENT);
                heading.setPadding(0, Ui.dp(this, lastDay == null ? 4 : 14), 0, Ui.dp(this, 2));
                historyList.addView(heading);
                lastDay = day;
            }
            long over = r.overMs();
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
            String what = r.label.isEmpty() ? "Timer" : r.label;
            TextView left = Ui.text(this, time.format(new java.util.Date(r.startedAt)) + "  " + what
                    + "\nplanned " + Ui.clock(r.plannedMs, false), 15, Ui.MUTED);
            line.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            int color = over <= 0 ? Ui.GREEN : (over < 3 * 60000 ? Ui.AMBER : Ui.RED);
            TextView right = Ui.text(this, Ui.signed(over) + (r.confirmed ? "" : "?"), 20, color);
            right.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            line.addView(right);
            historyList.addView(line);
        }
        if (runs.size() > RUNS_FOLDED) {
            TextView toggle = Ui.text(this, showAllRuns ? "Show fewer" : "Show all " + runs.size() + " runs", 16, Ui.ACCENT);
            toggle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showAllRuns = !showAllRuns;
                    renderHistory();
                }
            });
            historyList.addView(toggle);
        }
    }

    /** "Today", "Yesterday", or e.g. "Mon 12 Oct". */
    private static String dayName(long wall) {
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(wall);
        Calendar day = Calendar.getInstance();
        for (int back = 0; back < 2; back++) {
            if (day.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                    && day.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
                return back == 0 ? "Today" : "Yesterday";
            }
            day.add(Calendar.DAY_OF_YEAR, -1);
        }
        return new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(then.getTime());
    }
}
