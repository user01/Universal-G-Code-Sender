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
package com.willwinder.ugs.nbp.bridge.runtime;

import com.google.gson.JsonObject;
import com.willwinder.ugs.nbp.bridge.job.MachineProfile;

import java.util.ArrayList;
import java.util.List;

/** Shared test data for the runtime tests (and the simulator integration test). */
final class Fixtures {
    private Fixtures() {
    }

    /** Two tools with names in the header, G53 retracts before each M6, the post's G4 P2.0 after M3. */
    static final String TWO_TOOLS = """
            (CLEARANCE: every rapid/retract at Z+5 [work]. Tool-change park is G53 machine Z-5.)
            (TOOL [FIRST]: T02 Whiteside RD2100 1/4 in downcut   feed 1778 / plunge 508 mm/min)
            (  LOAD:  T21 Whiteside RU1600 1/8 in upcut   feed 1143 / plunge 381 mm/min)
            G21 G90 G94 G17
            G54
            G53 G0 Z-5.000
            T2 M6
            S18000 M3
            G4 P2.0
            (---- OP 01 | T02 | pocket | a test pocket ----)
            G0 X10 Y10
            G0 Z5
            G1 Z-2 F508
            X20 F1778
            Y20
            X10
            G0 Z5
            G53 G0 Z-5.000
            M5
            T21 M6
            S18000 M3
            G4 P2.0
            (---- OP 02 | T21 | finish | a test finish ----)
            G0 X30 Y30
            G0 Z5
            G1 Z-1 F381
            G1 X40 F1143
            G0 Z5
            G53 G0 Z-5
            M5
            M2
            """;

    static MachineProfile profile() {
        return MachineProfile.fromJson("""
                {"format": "ugs-bridge-profile/1", "name": "test",
                 "envelope_mm": {"min": [-445, -456, -95], "max": [0, 0, 0], "verified": true},
                 "safe_z_machine": -5.0,
                 "tool_change_park": [-222, -440, -5],
                 "bitsetter": {"xy": [-4, -449], "start_z_machine": -5, "max_travel": 90, "fast_feed": 800,
                               "slow_feed": 100, "retract": 2, "repeat_tolerance": 0.05, "pin": "P", "verified": true},
                 "bitzero": {"plate_thickness": 13, "max_travel": 25, "fast_feed": 200, "slow_feed": 50, "retract": 2, "pin": "P"},
                 "spindle": {"control": "bitrunner", "spin_up_s": 3.0}}
                """);
    }

    /** Collects {@code tool} records and origin expectations. */
    static final class Events implements ToolChangeRoutine.Events {
        final List<JsonObject> tools = new ArrayList<>();
        final List<String> expects = new ArrayList<>();

        @Override
        public void tool(JsonObject data) {
            tools.add(data);
        }

        @Override
        public void expectOrigin(String method, String tool, String note) {
            expects.add(method + "|" + tool + "|" + note);
        }

        List<String> toolEvents() {
            return tools.stream().map(d -> d.get("event").getAsString()).toList();
        }
    }

    /** Collects log records. */
    static final class Recs implements Records {
        record Rec(String kind, String src, JsonObject data) {
        }

        final List<Rec> all = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void append(String kind, String src, JsonObject data) {
            all.add(new Rec(kind, src, data));
        }

        List<JsonObject> of(String kind) {
            return all.stream().filter(r -> r.kind.equals(kind)).map(Rec::data).toList();
        }
    }
}
