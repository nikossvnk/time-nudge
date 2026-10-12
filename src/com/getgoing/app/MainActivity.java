package com.getgoing.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
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
        col.addView(Ui.text(this, "Long-press a preset to delete it.", 14, Ui.MUTED), fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 6));

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
        runningBanner.setVisibility(TimerService.running ? View.VISIBLE : View.GONE);
        renderPresets();
        renderHistory();
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
        for (int i = 0; i < presets.size(); i++) {
            final Store.Preset p = presets.get(i);
            final int index = i;
            Button b = Ui.button(this, p.name + "  ·  " + p.minutes + " min", Ui.CARD, 20);
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            b.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
            presetList.addView(b, fullWidth(64, i == 0 ? 0 : 8));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startTimer(p.minutes * 60000L, p.name);
                }
            });
            b.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setMessage("Delete \"" + p.name + "\"?")
                            .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    presets.remove(index);
                                    Store.savePresets(MainActivity.this, presets);
                                    renderPresets();
                                }
                            })
                            .setNegativeButton("Keep", null)
                            .show();
                    return true;
                }
            });
        }
    }

    private void renderHistory() {
        historyList.removeAllViews();
        List<Store.Run> runs = Store.history(this);
        if (runs.isEmpty()) {
            historySummary.setText("No runs yet. Your real times will show up here.");
            return;
        }
        int n = 0, onTime = 0;
        long overSum = 0;
        double ratioSum = 0;
        for (Store.Run r : runs) {
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
        historySummary.setText(sb.toString());

        SimpleDateFormat fmt = new SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault());
        for (int i = 0; i < runs.size() && i < 15; i++) {
            Store.Run r = runs.get(i);
            long over = r.overMs();
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            String what = r.label.isEmpty() ? "Timer" : r.label;
            TextView left = Ui.text(this, fmt.format(new java.util.Date(r.startedAt)) + "\n" + what
                    + "  ·  planned " + Ui.clock(r.plannedMs, false), 15, Ui.MUTED);
            line.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            int color = over <= 0 ? Ui.GREEN : (over < 3 * 60000 ? Ui.AMBER : Ui.RED);
            TextView right = Ui.text(this, Ui.signed(over) + (r.confirmed ? "" : "?"), 20, color);
            right.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            line.addView(right);
            historyList.addView(line);
        }
    }
}
