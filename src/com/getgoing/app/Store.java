package com.getgoing.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Presets and run history, kept as small JSON arrays in SharedPreferences. */
final class Store {
    private static final String PREFS = "getgoing";
    private static final String KEY_PRESETS = "presets";
    private static final String KEY_HISTORY = "history";
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
        final boolean confirmed;   // false when the timer auto-ended because nobody tapped "I'm out"

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

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<Preset> presets(Context c) {
        List<Preset> out = new ArrayList<Preset>();
        String raw = prefs(c).getString(KEY_PRESETS, null);
        if (raw == null) {
            out.add(new Preset("Leave for work", 15));
            out.add(new Preset("Quick errand", 10));
            out.add(new Preset("Gym", 20));
            savePresets(c, out);
            return out;
        }
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Preset(o.getString("name"), o.getInt("minutes")));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    static void savePresets(Context c, List<Preset> presets) {
        JSONArray a = new JSONArray();
        try {
            for (Preset p : presets) {
                a.put(new JSONObject().put("name", p.name).put("minutes", p.minutes));
            }
        } catch (JSONException ignored) {
        }
        prefs(c).edit().putString(KEY_PRESETS, a.toString()).apply();
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
        } catch (JSONException ignored) {
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
        prefs(c).edit().putString(KEY_HISTORY, a.toString()).apply();
    }

    static void clearHistory(Context c) {
        prefs(c).edit().remove(KEY_HISTORY).apply();
    }
}
