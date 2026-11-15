/*
    Copyright 2026 ugs-bridge contributors

    This file is part of Universal Gcode Sender (UGS).

    UGS is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    UGS is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with UGS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.willwinder.ugs.nbp.bridge.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Arrays;
import java.util.Map;

/**
 * Turns a raw GRBL 1.1 / grblHAL {@code <...>} status report into the {@code ControllerStatus} object of
 * {@code bridge/schema/status.schema.json}. Values are as reported by the controller (machine units per
 * {@code $13}). Fields GRBL only reports intermittently (WCO, Ov, A) are carried over from earlier
 * reports, as GRBL intends. One instance per connection; call {@link #reset()} on connect.
 */
public final class GrblStatusParser {
    private static final Map<String, String> STATES = Map.ofEntries(
            Map.entry("idle", "Idle"), Map.entry("run", "Run"), Map.entry("hold", "Hold"),
            Map.entry("jog", "Jog"), Map.entry("alarm", "Alarm"), Map.entry("door", "Door"),
            Map.entry("check", "Check"), Map.entry("home", "Home"), Map.entry("sleep", "Sleep"),
            Map.entry("tool", "Tool"));

    private double[] wco;
    private JsonObject overrides;
    private JsonObject accessories = accessories("");

    public void reset() {
        wco = null;
        overrides = null;
        accessories = accessories("");
    }

    public static boolean isStatusReport(String line) {
        return line != null && line.length() > 2 && line.charAt(0) == '<' && line.charAt(line.length() - 1) == '>';
    }

    /** Parses a report; returns null if it isn't a GRBL 1.1 style report. */
    public JsonObject parse(String raw) {
        if (!isStatusReport(raw)) {
            return null;
        }
        String body = raw.substring(1, raw.length() - 1);
        String[] fields = body.split("\\|");
        if (fields.length == 0 || fields[0].contains(",")) {
            return null; // GRBL 0.9 comma format: not supported here, the UGS status event is used instead
        }

        String[] st = fields[0].split(":", 2);
        String state = STATES.getOrDefault(st[0].toLowerCase(), "Unknown");
        Integer substate = st.length > 1 ? parseInt(st[1]) : null;

        double[] mpos = null;
        double[] wpos = null;
        Double feed = null;
        Double spindle = null;
        JsonObject buffer = null;
        Integer line = null;
        String pins = "";
        boolean sawOverrides = false;
        String acc = null;

        for (int i = 1; i < fields.length; i++) {
            String[] kv = fields[i].split(":", 2);
            if (kv.length < 2) {
                continue;
            }
            String v = kv[1];
            switch (kv[0]) {
                case "MPos" -> mpos = parseXyz(v);
                case "WPos" -> wpos = parseXyz(v);
                case "WCO" -> wco = parseXyz(v);
                case "FS" -> {
                    double[] fs = parseNumbers(v);
                    if (fs.length > 0) feed = fs[0];
                    if (fs.length > 1) spindle = fs[1];
                }
                case "F" -> {
                    double[] f = parseNumbers(v);
                    if (f.length > 0) feed = f[0];
                }
                case "Bf" -> {
                    double[] bf = parseNumbers(v);
                    if (bf.length >= 2) {
                        buffer = new JsonObject();
                        buffer.addProperty("planner", (int) bf[0]);
                        buffer.addProperty("rx", (int) bf[1]);
                    }
                }
                case "Ln" -> line = parseInt(v);
                case "Pn" -> pins = v;
                case "Ov" -> {
                    double[] ov = parseNumbers(v);
                    if (ov.length >= 3) {
                        overrides = new JsonObject();
                        overrides.addProperty("feed", (int) ov[0]);
                        overrides.addProperty("rapid", (int) ov[1]);
                        overrides.addProperty("spindle", (int) ov[2]);
                        sawOverrides = true;
                    }
                }
                case "A" -> acc = v;
                default -> {
                    // unknown fields are kept only in raw
                }
            }
        }
        // GRBL sends A: together with Ov:. Ov without A means all accessories off; neither means unchanged.
        if (acc != null) {
            accessories = accessories(acc);
        } else if (sawOverrides) {
            accessories = accessories("");
        }
        if (wco != null) {
            if (mpos != null && wpos == null) {
                wpos = new double[]{mpos[0] - wco[0], mpos[1] - wco[1], mpos[2] - wco[2]};
            } else if (wpos != null && mpos == null) {
                mpos = new double[]{wpos[0] + wco[0], wpos[1] + wco[1], wpos[2] + wco[2]};
            }
        }

        JsonObject o = new JsonObject();
        o.addProperty("state", state);
        if (substate != null) {
            o.addProperty("substate", substate);
        } else {
            o.add("substate", JsonNull.INSTANCE);
        }
        o.add("mpos", array(mpos));
        o.add("wpos", array(wpos));
        o.add("wco", array(wco));
        o.add("feed", feed == null ? JsonNull.INSTANCE : new JsonPrimitive(feed));
        o.add("spindle", spindle == null ? JsonNull.INSTANCE : new JsonPrimitive(spindle));
        o.add("overrides", overrides == null ? JsonNull.INSTANCE : overrides.deepCopy());
        o.add("pins", pins(pins));
        o.add("accessories", accessories.deepCopy());
        o.add("buffer", buffer == null ? JsonNull.INSTANCE : buffer);
        if (line != null) {
            o.addProperty("line", line);
        } else {
            o.add("line", JsonNull.INSTANCE);
        }
        o.addProperty("raw", raw);
        return o;
    }

    static JsonObject pins(String raw) {
        JsonObject p = new JsonObject();
        p.addProperty("raw", raw);
        p.addProperty("probe", raw.indexOf('P') >= 0);
        p.addProperty("x_limit", raw.indexOf('X') >= 0);
        p.addProperty("y_limit", raw.indexOf('Y') >= 0);
        p.addProperty("z_limit", raw.indexOf('Z') >= 0);
        p.addProperty("door", raw.indexOf('D') >= 0);
        p.addProperty("hold", raw.indexOf('H') >= 0);
        p.addProperty("reset", raw.indexOf('R') >= 0);
        p.addProperty("start", raw.indexOf('S') >= 0);
        return p;
    }

    static JsonObject accessories(String raw) {
        JsonObject a = new JsonObject();
        a.addProperty("spindle_cw", raw.indexOf('S') >= 0);
        a.addProperty("spindle_ccw", raw.indexOf('C') >= 0);
        a.addProperty("flood", raw.indexOf('F') >= 0);
        a.addProperty("mist", raw.indexOf('M') >= 0);
        return a;
    }

    static JsonArray arrayOf(double x, double y, double z) {
        JsonArray a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        return a;
    }

    private static JsonElement array(double[] v) {
        if (v == null) {
            return JsonNull.INSTANCE;
        }
        return arrayOf(v[0], v[1], v[2]);
    }

    private static double[] parseXyz(String v) {
        double[] n = parseNumbers(v);
        return n.length >= 3 ? new double[]{n[0], n[1], n[2]} : null;
    }

    private static double[] parseNumbers(String v) {
        String[] parts = v.split(",");
        double[] out = new double[parts.length];
        int k = 0;
        for (String p : parts) {
            try {
                out[k++] = Double.parseDouble(p.trim());
            } catch (NumberFormatException e) {
                return Arrays.copyOf(out, k - 1);
            }
        }
        return out;
    }

    private static Integer parseInt(String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
