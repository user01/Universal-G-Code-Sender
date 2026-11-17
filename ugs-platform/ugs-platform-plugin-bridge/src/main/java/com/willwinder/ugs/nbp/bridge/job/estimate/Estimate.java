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
package com.willwinder.ugs.nbp.bridge.job.estimate;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * A job's time estimate ({@code GET /file/estimate}). Times are seconds of machine time from the start of the file;
 * tool changes add the automatic BitSetter cycle ({@link #bitsetterS}) and the operator's time (unknown).
 *
 * @param lineEndS     cumulative machine seconds at the end of each file line (index 0 = line 1)
 * @param machineS     motion and dwells, without tool changes
 * @param bitsetterS   the automatic part of all tool changes (park, spin-down wait, BitSetter, return)
 * @param naiveS       distance / feed with rapids at the max rate and no acceleration, for comparison
 */
public record Estimate(String sha256, String name, int lines, MotionSettings settings, double[] lineEndS,
                       double machineS, double bitsetterS, double naiveS, List<Op> ops, List<Tool> tools,
                       List<ToolChange> toolChanges, List<String> assumptions) {

    public static final int VERSION = 1;

    public Estimate {
        ops = List.copyOf(ops);
        tools = List.copyOf(tools);
        toolChanges = List.copyOf(toolChanges);
        assumptions = List.copyOf(assumptions);
    }

    /** One operation: file lines {@code firstLine..lastLine} (1-based), running from {@code startS} to {@code endS}. */
    public record Op(int index, String name, String tool, int firstLine, int lastLine, double startS, double endS) {
        public double seconds() {
            return endS - startS;
        }
    }

    /** Machine seconds spent with a tool in the collet (tool 0: before the first {@code M6}). */
    public record Tool(int number, String id, String name, double seconds) {
    }

    /**
     * An {@code M6}: at machine time {@code atS}, the automatic cycle takes about {@code bitsetterS}; the operator's
     * time is unknown.
     */
    public record ToolChange(int line, Integer from, int to, double atS, double bitsetterS) {
    }

    public double totalS() {
        return machineS + bitsetterS;
    }

    /** Machine seconds at the end of file line {@code line} (1-based; 0 = before the first line). */
    public double atLine(int line) {
        if (line <= 0 || lineEndS.length == 0) {
            return 0;
        }
        return lineEndS[Math.min(line, lineEndS.length) - 1];
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("version", VERSION);
        o.addProperty("source", "grbl-planner");
        o.addProperty("settings_from", settings.source());
        o.addProperty("file", name);
        o.addProperty("sha256", sha256);
        o.addProperty("lines", lines);
        o.addProperty("total_s", r2(totalS()));
        o.addProperty("machine_s", r2(machineS));
        o.addProperty("bitsetter_s", r2(bitsetterS));
        o.add("operator_s", JsonNull.INSTANCE);
        o.addProperty("naive_s", r2(naiveS));
        JsonObject st = new JsonObject();
        st.add("max_rate_mm_min", arr(settings.maxRateMmMin()));
        st.add("accel_mm_s2", arr(settings.accelMmS2()));
        st.addProperty("junction_deviation_mm", settings.junctionDeviationMm());
        st.addProperty("arc_tolerance_mm", settings.arcToleranceMm());
        st.addProperty("planner_blocks", settings.plannerBlocks());
        o.add("settings", st);
        JsonArray le = new JsonArray(lineEndS.length);
        for (double v : lineEndS) {
            le.add(r2(v));
        }
        o.add("line_end_s", le);
        JsonArray ja = new JsonArray();
        for (Op op : ops) {
            JsonObject j = new JsonObject();
            j.addProperty("index", op.index());
            j.addProperty("name", op.name());
            j.addProperty("tool", op.tool());
            j.addProperty("first_line", op.firstLine());
            j.addProperty("last_line", op.lastLine());
            j.addProperty("start_s", r2(op.startS()));
            j.addProperty("end_s", r2(op.endS()));
            ja.add(j);
        }
        o.add("ops", ja);
        JsonArray ta = new JsonArray();
        for (Tool t : tools) {
            JsonObject j = new JsonObject();
            j.addProperty("tool", t.id());
            j.addProperty("number", t.number());
            j.addProperty("name", t.name());
            j.addProperty("s", r2(t.seconds()));
            ta.add(j);
        }
        o.add("tools", ta);
        JsonArray ca = new JsonArray();
        for (ToolChange c : toolChanges) {
            JsonObject j = new JsonObject();
            j.addProperty("line", c.line());
            j.addProperty("from", c.from() == null ? null : "T" + c.from());
            j.addProperty("to", "T" + c.to());
            j.addProperty("at_s", r2(c.atS()));
            j.addProperty("bitsetter_s", r2(c.bitsetterS()));
            j.add("operator_s", JsonNull.INSTANCE);
            ca.add(j);
        }
        o.add("tool_changes", ca);
        JsonArray aa = new JsonArray();
        assumptions.forEach(aa::add);
        o.add("assumptions", aa);
        return o;
    }

    static double r2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static JsonArray arr(double[] v) {
        JsonArray a = new JsonArray();
        for (double d : v) {
            a.add(d);
        }
        return a;
    }
}
