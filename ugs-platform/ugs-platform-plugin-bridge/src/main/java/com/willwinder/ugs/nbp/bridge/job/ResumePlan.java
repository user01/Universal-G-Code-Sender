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
package com.willwinder.ugs.nbp.bridge.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A resume plan: {@code bridge/schema/job-state.schema.json#/$defs/ResumePlan}. Fields after {@code warnings} are
 * additive (the schema allows additional properties). Computing a plan sends nothing.
 */
public class ResumePlan {
    public static final String HOME = "home";
    public static final String RESTORE_ORIGIN = "restore_origin";
    public static final String TOOL_CHANGE = "tool_change";
    public static final String TOOL_MEASURE = "tool_measure";
    public static final String CONFIRM_MID_CONTOUR = "operator_confirm_mid_contour";

    public String planId;
    public String fileSha256;
    public Map<String, Object> requested;
    public int resumeLine;
    public boolean safePoint;
    public String operation;
    public String tool;
    public List<Check> checks = new ArrayList<>();
    public List<String> requires = new ArrayList<>();
    public List<String> preamble = new ArrayList<>();
    public List<String> warnings = new ArrayList<>();

    // ---- additive fields
    /** Line the request resolved to before safe-point snapping (for "stopped": the finder's earliest line). */
    public Integer candidateLine;
    /** {@code position}, {@code buffer_backoff}, {@code completed} (stopped target only). */
    public String finderMethod;
    /**
     * Work position the preamble brings the tool to before streaming {@code resume_line} (file units); null for an
     * axis whose position is unknown (e.g. Z after a {@code G53} park: the tool stays at machine safe Z).
     */
    public Double[] startPosition;
    public Double clearanceZWork;
    /** mm/min (as the header/sidecar give it). */
    public Double plungeFeed;
    /** Modal feed restored after the plunge (file units/min). */
    public Double cutFeed;
    /** M6 line the stream will run before cutting, if the resume starts in a tool-change block. */
    public Integer toolChangeInStream;
    /** True when all checks pass and nothing is required: the plan can run after the operator confirms. */
    public boolean ready;

    public static class Check {
        public String name;
        public boolean ok;
        public String detail;

        public Check() {
        }

        public Check(String name, boolean ok, String detail) {
            this.name = name;
            this.ok = ok;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return (ok ? "[ok] " : "[!!] ") + name + (detail == null ? "" : ": " + detail);
        }
    }

    public Check check(String name) {
        return checks.stream().filter(c -> c.name.equals(name)).findFirst().orElse(null);
    }

    /** Plain-text rendering for the panel / MCP. */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Resume at line ").append(resumeLine);
        if (operation != null) {
            sb.append(" (").append(operation).append(')');
        }
        if (tool != null) {
            sb.append(", tool ").append(tool);
        }
        sb.append(safePoint ? ", safe point" : ", MID-CONTOUR").append('\n');
        checks.forEach(c -> sb.append("  ").append(c).append('\n'));
        if (!requires.isEmpty()) {
            sb.append("Requires: ").append(String.join(", ", requires)).append('\n');
        }
        sb.append("Preamble:\n");
        preamble.forEach(l -> sb.append("  ").append(l).append('\n'));
        warnings.forEach(w -> sb.append("Warning: ").append(w).append('\n'));
        return sb.toString();
    }
}
