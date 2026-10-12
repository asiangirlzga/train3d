package com.trainrush.lite;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Colours and scenery settings for one look of the game.
 * Pure data: themes are loaded from JSON, so a theme pack can never contain code.
 * Any field missing from the JSON falls back to the "Sunny" defaults below.
 */
final class Theme {
    static final int SC_PINE = 0, SC_CACTUS = 1, SC_CITY = 2, SC_ROUND = 3;

    String id = "sunny";
    String name = "Sunny Countryside";

    float[] skyTop = rgb(0x4FA9F5);
    float[] skyBottom = rgb(0xCDEBFF);   // also used as the fog colour
    float[] ground = rgb(0x5DAA46);
    float[] ballast = rgb(0x8C8579);
    float[] sleeper = rgb(0x5B3F2A);
    float[] rail = rgb(0xC9CED6);

    float[] train = rgb(0xD63B3B);
    float[] accent = rgb(0xFFD23F);
    float[] car = rgb(0x3B6FD6);
    float[] enemy = rgb(0x2B2F3A);
    float[] barrier = rgb(0xE74C3C);
    float[] rock = rgb(0x7A7468);
    float[] coin = rgb(0xFFD700);

    float[] sceneryA = rgb(0x2F7D32);
    float[] sceneryB = rgb(0x5B3A1E);
    int scenery = SC_PINE;

    float fogStart = 35f;
    float fogEnd = 100f;
    float ambient = 0.55f;

    int bpm = 128;   // music tempo
    int root = 57;   // music root note (MIDI number, 57 = A3)

    // ---------------------------------------------------------------- helpers

    static float[] rgb(int hex) {
        return new float[]{((hex >> 16) & 255) / 255f, ((hex >> 8) & 255) / 255f, (hex & 255) / 255f};
    }

    private static float[] color(JSONObject o, String key, float[] def) {
        String s = o.optString(key, null);
        if (s == null) return def;
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6) return def;
        try {
            return rgb(Integer.parseInt(s, 16));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static float num(JSONObject o, String key, float def, float lo, float hi) {
        double v = o.optDouble(key, def);
        if (Double.isNaN(v)) return def;
        return (float) Math.max(lo, Math.min(hi, v));
    }

    private static int sceneryFromName(String s) {
        if ("cactus".equals(s)) return SC_CACTUS;
        if ("city".equals(s)) return SC_CITY;
        if ("round".equals(s)) return SC_ROUND;
        return SC_PINE;
    }

    /** Returns null if the object has no usable id. */
    static Theme fromJson(JSONObject o) {
        String id = o.optString("id", "").trim();
        if (id.length() == 0 || id.length() > 40) return null;
        Theme t = new Theme();
        t.id = id;
        String n = o.optString("name", id).trim();
        t.name = n.length() == 0 ? id : (n.length() > 32 ? n.substring(0, 32) : n);

        t.skyTop = color(o, "skyTop", t.skyTop);
        t.skyBottom = color(o, "skyBottom", t.skyBottom);
        t.ground = color(o, "ground", t.ground);
        t.ballast = color(o, "ballast", t.ballast);
        t.sleeper = color(o, "sleeper", t.sleeper);
        t.rail = color(o, "rail", t.rail);
        t.train = color(o, "train", t.train);
        t.accent = color(o, "accent", t.accent);
        t.car = color(o, "car", t.car);
        t.enemy = color(o, "enemy", t.enemy);
        t.barrier = color(o, "barrier", t.barrier);
        t.rock = color(o, "rock", t.rock);
        t.coin = color(o, "coin", t.coin);
        t.sceneryA = color(o, "sceneryA", t.sceneryA);
        t.sceneryB = color(o, "sceneryB", t.sceneryB);
        t.scenery = sceneryFromName(o.optString("scenery", "pine"));

        t.fogStart = num(o, "fogStart", t.fogStart, 5f, 150f);
        t.fogEnd = num(o, "fogEnd", t.fogEnd, 20f, 160f);
        if (t.fogEnd < t.fogStart + 10f) t.fogEnd = t.fogStart + 10f;
        t.ambient = num(o, "ambient", t.ambient, 0.2f, 1f);
        t.bpm = (int) num(o, "bpm", t.bpm, 70f, 180f);
        t.root = (int) num(o, "root", t.root, 36f, 72f);
        return t;
    }

    /** Parses a theme pack: {"themes":[{...},{...}]}. Invalid entries are skipped. */
    static List<Theme> parsePack(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.getJSONArray("themes");
        ArrayList<Theme> out = new ArrayList<>();
        for (int i = 0; i < arr.length() && i < 24; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Theme t = fromJson(o);
            if (t != null) out.add(t);
        }
        return out;
    }

    /** Themes that ship inside the APK so the game works fully offline. */
    static List<Theme> builtins() {
        ArrayList<Theme> out = new ArrayList<>();
        out.add(new Theme());

        Theme n = new Theme();
        n.id = "night";
        n.name = "Midnight Express";
        n.skyTop = rgb(0x050A24);
        n.skyBottom = rgb(0x24346E);
        n.ground = rgb(0x1B3326);
        n.ballast = rgb(0x4A4A55);
        n.sleeper = rgb(0x2B1D14);
        n.rail = rgb(0x9AA3B5);
        n.train = rgb(0xC0392B);
        n.accent = rgb(0xF1C40F);
        n.car = rgb(0x2E5FA8);
        n.enemy = rgb(0x6B2FA0);
        n.barrier = rgb(0xFF5A36);
        n.rock = rgb(0x55556B);
        n.sceneryA = rgb(0x14452A);
        n.sceneryB = rgb(0x3A2A1C);
        n.scenery = SC_PINE;
        n.fogStart = 25f;
        n.fogEnd = 90f;
        n.ambient = 0.42f;
        n.bpm = 104;
        n.root = 52;
        out.add(n);
        return out;
    }
}
