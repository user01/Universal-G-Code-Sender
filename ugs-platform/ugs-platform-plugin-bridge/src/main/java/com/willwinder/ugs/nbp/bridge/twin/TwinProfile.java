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
package com.willwinder.ugs.nbp.bridge.twin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.willwinder.ugs.nbp.bridge.job.MachineProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the twin needs from the machine profile JSON, read from the raw text so the profile sections the Java model
 * doesn't carry ({@code bed}, {@code expected_settings}, {@code spoilboard_z_machine}) count too.
 */
public final class TwinProfile {
    /** An assumed (not measured) profile value: what it is and where the ledger says how to measure it. */
    public record Assumed(String path, String label, String ledger) {
    }

    final JsonObject raw;
    final MachineProfile model;
    final double[] bitsetterXy;
    final double bitsetterRadius;
    final double[] spoilboardMin;
    final double[] spoilboardMax;
    final boolean bedVerified;

    private TwinProfile(JsonObject raw, MachineProfile model) {
        this.raw = raw;
        this.model = model;
        this.bitsetterXy = pair(obj(raw, "bitsetter"), "xy");
        JsonObject bed = obj(raw, "bed");
        this.bitsetterRadius = bed != null && bed.has("bitsetter_radius") && bed.get("bitsetter_radius").isJsonPrimitive()
                ? bed.get("bitsetter_radius").getAsDouble() : 20;
        JsonObject sb = obj(bed, "spoilboard");
        this.spoilboardMin = pair(sb, "min_xy");
        this.spoilboardMax = pair(sb, "max_xy");
        this.bedVerified = bed != null && bed.has("verified") && bed.get("verified").isJsonPrimitive()
                && bed.get("verified").getAsBoolean();
    }

    /** Parses the profile JSON; null when it is missing or not an object. */
    public static TwinProfile parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) {
                return null;
            }
            return new TwinProfile(e.getAsJsonObject(), MachineProfile.fromJson(json));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public MachineProfile model() {
        return model;
    }

    /**
     * The profile values still marked assumed: every {@code verified: false} or {@code *_verified: false}, a
     * {@code *source} that starts with "assumed", a null {@code spoilboard_z_machine}, and the BitZero bore (XY
     * probing) while it is null.
     */
    public List<Assumed> assumed() {
        Map<String, Assumed> out = new LinkedHashMap<>();
        walk(raw, "", out);
        JsonObject bs = obj(raw, "bitsetter");
        if (out.containsKey("bitsetter") && bs != null && bs.has("xy_verified") && bs.get("xy_verified").isJsonPrimitive()
                && bs.get("xy_verified").getAsBoolean()) {
            // its position (and so where the twin draws it) is measured and confirmed; only the probe settings aren't
            out.put("bitsetter", new Assumed("bitsetter", "BitSetter probe settings (feeds, travel)", null));
        }
        if (raw.has("spoilboard_z_machine") && raw.get("spoilboard_z_machine").isJsonNull()) {
            put(out, "spoilboard_z_machine");
        }
        JsonObject bz = obj(raw, "bitzero");
        if (bz != null && (!bz.has("bore_center_to_corner") || bz.get("bore_center_to_corner").isJsonNull())) {
            put(out, "bitzero.bore_center_to_corner");
        }
        return new ArrayList<>(out.values());
    }

    private static void walk(JsonObject o, String prefix, Map<String, Assumed> out) {
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            String k = e.getKey();
            JsonElement v = e.getValue();
            String here = prefix.isEmpty() ? k : prefix + "." + k;
            if (v.isJsonObject()) {
                walk(v.getAsJsonObject(), here, out);
            } else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && !v.getAsBoolean()) {
                if (k.equals("verified")) {
                    put(out, prefix);
                } else if (k.endsWith("_verified")) {
                    put(out, (prefix.isEmpty() ? "" : prefix + ".") + k.substring(0, k.length() - "_verified".length()));
                }
            } else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() && k.endsWith("source")
                    && v.getAsString().toLowerCase(Locale.ROOT).startsWith("assumed")) {
                String stem = k.equals("source") ? prefix
                        : (prefix.isEmpty() ? "" : prefix + ".") + k.substring(0, k.length() - "_source".length());
                put(out, stem);
            }
        }
    }

    private static void put(Map<String, Assumed> out, String path) {
        if (path.isEmpty() || out.containsKey(path)) {
            return;
        }
        out.put(path, describe(path));
    }

    /** Plain label and ledger rows for a profile path. */
    static Assumed describe(String path) {
        return switch (path) {
            case "envelope_mm" -> new Assumed(path, "machine envelope", "A1");
            case "bitsetter" -> new Assumed(path, "BitSetter position", "B6");
            case "bitzero.plate_thickness" -> new Assumed(path, "BitZero plate thickness", "B1");
            case "bitzero.bore_center_to_corner" -> new Assumed(path, "BitZero bore offset (XY probing)", "B3");
            case "bed" -> new Assumed(path, "bed layout (spoilboard and frame)", "B11, B12");
            case "spoilboard_z_machine" -> new Assumed(path, "spoilboard height", "B9");
            case "spindle.spin_down" -> new Assumed(path, "router spin-down time", "C8");
            default -> new Assumed(path, path.replace('_', ' '), null);
        };
    }

    /**
     * Settings the controller should report: {@code expected_settings} plus the {@code motion} block
     * ($110-$112, $120-$122, $11, $12).
     */
    public Map<String, Double> expectedSettings() {
        Map<String, Double> out = new LinkedHashMap<>();
        JsonObject exp = obj(raw, "expected_settings");
        if (exp != null) {
            for (Map.Entry<String, JsonElement> e : exp.entrySet()) {
                if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) {
                    out.put(e.getKey(), e.getValue().getAsDouble());
                }
            }
        }
        JsonObject m = obj(raw, "motion");
        if (m != null) {
            triple(out, m, "max_rate_mm_min", 110);
            triple(out, m, "accel_mm_s2", 120);
            if (m.has("junction_deviation_mm") && m.get("junction_deviation_mm").isJsonPrimitive()) {
                out.put("$11", m.get("junction_deviation_mm").getAsDouble());
            }
            if (m.has("arc_tolerance_mm") && m.get("arc_tolerance_mm").isJsonPrimitive()) {
                out.put("$12", m.get("arc_tolerance_mm").getAsDouble());
            }
        }
        return out;
    }

    private static void triple(Map<String, Double> out, JsonObject m, String key, int first) {
        JsonElement e = m.get(key);
        if (e == null || !e.isJsonArray()) {
            return;
        }
        JsonArray a = e.getAsJsonArray();
        for (int i = 0; i < Math.min(3, a.size()); i++) {
            if (a.get(i).isJsonPrimitive()) {
                out.put("$" + (first + i), a.get(i).getAsDouble());
            }
        }
    }

    static JsonObject obj(JsonObject o, String key) {
        if (o == null) {
            return null;
        }
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    static double[] pair(JsonObject o, String key) {
        if (o == null) {
            return null;
        }
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() < 2) {
            return null;
        }
        JsonArray a = e.getAsJsonArray();
        try {
            return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble()};
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
