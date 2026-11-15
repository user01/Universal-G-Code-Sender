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

/** Small synthetic programs for the job tests. */
final class Programs {
    private Programs() {
    }

    /** Two tools, two pipe-style ops, an arc, clearance header, G53 parks. */
    static final String TWO_TOOLS = """
            (CLEARANCE: every rapid/retract at Z+5 [work]. Tool-change park is G53 machine Z-5.)
            (TOOL [FIRST]: T02 2-flute downcut spiral 1/4 in, Whiteside RD2100   feed 1778 / plunge 508 mm/min)
            G21 G90 G94 G17
            G54
            G53 G0 Z-5.000
            T2 M6
            S18000 M3
            (---- OP 01 | T02 | pocket | a test pocket ----)
            (     tool T02 2-flute downcut, feed 1778 / plunge 508 mm/min)
            G0 X10 Y10
            G0 Z5
            G1 Z-2 F508
            X20 F1778
            Y20
            X10
            G0 Z5
            G53 G0 Z-5.000
            M5
            (FEEDS: 1143 mm/min cut, 381 mm/min plunge, CSV DOC 1.27 mm)
            T21 M6
            S18000 M3
            (---- OP 02 | T21 | finish | a test finish ----)
            G0 X30 Y30
            G0 Z5
            G1 Z-1 F381
            G2 X40 Y30 I5 J0 F1143
            X30 Y30 I-5 J0
            G0 Z5
            G53 G0 Z-5
            M5
            M2
            """;

    /** A straight run of 40 feed moves along X at Z-1 (lines "X1" .. "X40"). */
    static String straightRun() {
        StringBuilder sb = new StringBuilder();
        sb.append("(CLEARANCE: Z+5)\n");
        sb.append("G21 G90 G17 G94 G54\n");
        sb.append("T1 M6\n");
        sb.append("S10000 M3\n");
        sb.append("G0 X0 Y0\n");
        sb.append("G0 Z5\n");
        sb.append("G1 Z-1 F100\n");
        for (int i = 1; i <= 40; i++) {
            sb.append("X").append(i).append(i == 1 ? " F1000" : "").append('\n');
        }
        sb.append("G0 Z5\n");
        sb.append("M5\n");
        return sb.toString();
    }

    static int lineOf(JobModel job, String text) {
        for (int i = 1; i <= job.lineCount(); i++) {
            if (job.line(i).trim().equals(text)) {
                return i;
            }
        }
        throw new AssertionError("no line '" + text + "'");
    }

    /** n-th (1-based) line with exactly this text. */
    static int lineOf(JobModel job, String text, int occurrence) {
        int seen = 0;
        for (int i = 1; i <= job.lineCount(); i++) {
            if (job.line(i).trim().equals(text) && ++seen == occurrence) {
                return i;
            }
        }
        throw new AssertionError("no occurrence " + occurrence + " of '" + text + "'");
    }

    static MachineProfile profile() {
        return MachineProfile.fromJson("""
                {"format": "ugs-bridge-profile/1", "name": "test",
                 "envelope_mm": {"min": [-445, -456, -95], "max": [0, 0, 0], "verified": true},
                 "safe_z_machine": -5.0,
                 "tool_change_park": [-222, -440, -5],
                 "bitsetter": {"xy": [-4, -449], "start_z_machine": -5, "max_travel": 90, "fast_feed": 800,
                               "slow_feed": 100, "retract": 2, "repeat_tolerance": 0.05, "pin": "P", "verified": true},
                 "bitzero": {"plate_thickness": null, "max_travel": 25, "fast_feed": 200, "slow_feed": 50, "retract": 2, "pin": "P"},
                 "spindle": {"control": "bitrunner", "spin_up_s": 3.0}}
                """);
    }

    static final double[] WCO = {-300, -350, -60};
}
