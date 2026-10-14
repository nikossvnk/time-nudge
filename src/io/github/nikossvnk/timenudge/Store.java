package io.github.nikossvnk.timenudge;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Presets, run history and the running timer, kept as small JSON values in SharedPreferences. */
final class Store {
    private static final String PREFS = "timenudge";
    private static final String KEY_PRESETS = "presets";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_ACTIVE = "active";
    private static final int MAX_HISTORY = 100;

    static final class Preset {
        final String name;
        final int minutes;

        Preset(String name, int minutes) {
            this.name = name;
            this.minutes = minutes;
        }
    }

    static final class Run {
        final long startedAt;      // wall clock, ms
        final String label;
        final long plannedMs;
        final long actualMs;
        final boolean confirmed;   // false when the timer auto-ended because nobody tapped "Done"

        Run(long startedAt, String label, long plannedMs, long actualMs, boolean confirmed) {
            this.startedAt = startedAt;
            this.label = label;
            this.plannedMs = plannedMs;
            this.actualMs = actualMs;
            this.confirmed = confirmed;
        }

        long overMs() {
            return actualMs - plannedMs;
        }
    }

    /** Runs that count for stats: auto-ended ones only show that nobody tapped Done. */
    static List<Run> confirmed(List<Run> runs) {
        List<Run> out = new ArrayList<Run>();
        for (Run r : runs) if (r.confirmed) out.add(r);
        return out;
    }

    /**
     * How long a preset usually really takes, in whole minutes: the median of its last 10
     * confirmed runs. Null until there are at least 3.
     */
    static Integer typicalMinutes(List<Run> runs, String label) {
        List<Long> times = new ArrayList<Long>();
        for (Run r : confirmed(runs)) {
            if (r.label.equals(label)) times.add(r.actualMs);
            if (times.size() == 10) break;
        }
        if (times.size() < 3) return null;
        Collections.sort(times);
        int n = times.size();
        long median = n % 2 == 1 ? times.get(n / 2) : (times.get(n / 2 - 1) + times.get(n / 2)) / 2;
        return (int) Math.max(1, Math.round(median / 60000.0));
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<Preset> presets(Context c) {
        List<Preset> out = new ArrayList<Preset>();
        String raw = prefs(c).getString(KEY_PRESETS, null);
        if (raw == null) {
            out.add(new Preset("Get ready to leave", 15));
            out.add(new Preset("Quick task", 10));
            out.add(new Preset("Focus block", 25));
            savePresets(c, out);
            return out;
        }
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Preset(o.getString("name"), o.getInt("minutes")));
            }
        } catch (JSONException e) {
            // Unreadable: show what was read so far, but don't let a save overwrite the rest.
            presetsUnreadable = true;
        }
        return out;
    }

    /** Set when stored data couldn't be read, so saving can't silently replace it. */
    private static boolean presetsUnreadable, historyUnreadable;

    static void savePresets(Context c, List<Preset> presets) {
        JSONArray a = new JSONArray();
        try {
            for (Preset p : presets) {
                a.put(new JSONObject().put("name", p.name).put("minutes", p.minutes));
            }
        } catch (JSONException ignored) {
        }
        if (!presetsUnreadable) prefs(c).edit().putString(KEY_PRESETS, a.toString()).apply();
    }

    /** Newest first. */
    static List<Run> history(Context c) {
        List<Run> out = new ArrayList<Run>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString(KEY_HISTORY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Run(o.getLong("at"), o.optString("label", ""), o.getLong("planned"),
                        o.getLong("actual"), o.optBoolean("confirmed", true)));
            }
        } catch (JSONException e) {
            historyUnreadable = true;
        }
        return out;
    }

    static void addRun(Context c, Run run) {
        List<Run> runs = history(c);
        runs.add(0, run);
        JSONArray a = new JSONArray();
        try {
            for (int i = 0; i < runs.size() && i < MAX_HISTORY; i++) {
                Run r = runs.get(i);
                a.put(new JSONObject().put("at", r.startedAt).put("label", r.label)
                        .put("planned", r.plannedMs).put("actual", r.actualMs)
                        .put("confirmed", r.confirmed));
            }
        } catch (JSONException ignored) {
        }
        if (!historyUnreadable) prefs(c).edit().putString(KEY_HISTORY, a.toString()).apply();
    }

    static void clearHistory(Context c) {
        prefs(c).edit().remove(KEY_HISTORY).apply();
        historyUnreadable = false;
    }

    /** The running timer's saved state, or null if none. */
    static JSONObject activeTimer(Context c) {
        String raw = prefs(c).getString(KEY_ACTIVE, null);
        if (raw == null) return null;
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            return null;
        }
    }

    static void saveActiveTimer(Context c, JSONObject state) {
        prefs(c).edit().putString(KEY_ACTIVE, state.toString()).apply();
    }

    static void clearActiveTimer(Context c) {
        prefs(c).edit().remove(KEY_ACTIVE).apply();
    }
}
