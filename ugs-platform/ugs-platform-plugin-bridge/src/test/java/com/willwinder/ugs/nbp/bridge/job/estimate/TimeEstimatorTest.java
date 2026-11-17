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

import com.google.gson.JsonObject;
import com.willwinder.ugs.nbp.bridge.job.JobModel;
import com.willwinder.ugs.nbp.bridge.job.MachineProfile;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Hand-computed GRBL planner cases (accel 500 mm/s² on X/Y, $11 = 0.02). */
public class TimeEstimatorTest {
    static final MotionSettings S = new MotionSettings(new double[]{10000, 10000, 1000}, new double[]{500, 500, 270},
            0.02, 0.01, 14, "profile", List.of());

    private static double plan(MotionSettings s, int horizon, double[][] moves, double feed) {
        Planner p = new Planner(s, horizon);
        for (double[] m : moves) {
            p.add(m, false, feed, 1);
        }
        double sum = 0;
        for (double t : p.times()) {
            sum += t;
        }
        return sum;
    }

    @Test
    public void aLongMoveIsATrapezoid() {
        // 100 mm at 100 mm/s: 10 mm up, 80 mm cruise, 10 mm down = 0.2 + 0.8 + 0.2 s
        assertEquals(1.2, plan(S, 13, new double[][]{{100, 0, 0}}, 6000), 1e-9);
    }

    @Test
    public void aShortMoveNeverReachesTheFeed() {
        // 5 mm: peak² = a·L = 2500, peak 50 mm/s, 0.1 s up + 0.1 s down
        assertEquals(0.2, plan(S, 13, new double[][]{{5, 0, 0}}, 6000), 1e-9);
    }

    @Test
    public void collinearMovesDontSlowDown() {
        assertEquals(1.2, plan(S, 13, new double[][]{{50, 0, 0}, {50, 0, 0}}, 6000), 1e-9);
    }

    @Test
    public void aRightAngleCornerSlowsToTheJunctionSpeed() {
        // junction accel 500/cos45 = 707.1; sin(θ/2) = √½; v² = 707.1·0.02·0.7071/(1−0.7071) = 34.14, v = 5.843 mm/s
        double v = Math.sqrt(707.1068 * 0.02 * Math.sqrt(0.5) / (1 - Math.sqrt(0.5)));
        double block = 0.2 + (100 - v) / 500 + (50 - 10 - (10000 - v * v) / 1000) / 100;
        assertEquals(2 * block, plan(S, 13, new double[][]{{50, 0, 0}, {0, 50, 0}}, 6000), 1e-4);
        assertEquals(1.3773, plan(S, 13, new double[][]{{50, 0, 0}, {0, 50, 0}}, 6000), 1e-3);
    }

    @Test
    public void aReversalStops() {
        // each 50 mm leg from rest to rest: 0.2 + 0.3 + 0.2 = 0.7 s
        assertEquals(1.4, plan(S, 13, new double[][]{{50, 0, 0}, {-50, 0, 0}}, 6000), 1e-9);
    }

    @Test
    public void ratesAndAccelerationsAreLimitedPerAxis() {
        // Z only: rate 1000 mm/min = 16.67 mm/s, accel 270; 30 mm: up/down 0.0617 s, 0.514 mm each
        double v = 1000 / 60.0;
        double t = 2 * v / 270 + (30 - v * v / 270) / v;
        Planner p = new Planner(S, 13);
        p.add(new double[]{0, 0, 30}, true, 0, 1);
        assertEquals(t, p.times()[0], 1e-9);
    }

    @Test
    public void shortLookAheadSlowsDenseSegments() {
        double[][] many = new double[200][];
        for (int i = 0; i < many.length; i++) {
            many[i] = new double[]{0.1, 0, 0};
        }
        double full = plan(S, 0, many, 6000);
        double window = plan(S, 13, many, 6000);
        // 20 mm with unlimited look-ahead: 0.2 + 0.0 cruise... peak² = 500·20 = 10000 → reaches 100 mm/s exactly
        assertEquals(0.4, full, 1e-6);
        // 13 blocks of 0.1 mm = 1.3 mm to stop in: v² ≤ 2·500·1.3, v ≤ 36 mm/s
        assertTrue("window " + window + " vs full " + full, window > full * 1.3);
    }

    private static Estimate est(String program, MachineProfile p) {
        JobModel job = JobModel.parse("t.nc", program, null);
        return TimeEstimator.estimate(job, new TimeEstimator.Inputs(S, new double[]{-300, -300, -50},
                new double[]{-300, -300, -5}, p, false));
    }

    @Test
    public void linesGetCumulativeTimesAndDwellsCount() {
        Estimate e = est("""
                G21 G90
                G1 X100 F6000
                G4 P2.5
                (comment)
                G1 X200
                """, null);
        assertEquals(5, e.lineEndS().length);
        assertEquals(0, e.lineEndS()[0], 1e-9);
        assertEquals(1.2, e.lineEndS()[1], 1e-9);
        assertEquals(3.7, e.lineEndS()[2], 1e-9);
        assertEquals(3.7, e.lineEndS()[3], 1e-9);
        assertEquals(4.9, e.lineEndS()[4], 1e-9);
        assertEquals(4.9, e.machineS(), 1e-9);
        assertEquals(4.5, e.naiveS(), 1e-9); // 100 mm at 100 mm/s twice, no acceleration, + 2.5 s
    }

    @Test
    public void g53MovesUseMachineCoordinates() {
        Estimate e = est("""
                G21 G90
                G53 G0 Z-35
                """, null);
        // -5 → -35: 30 mm on Z
        double v = 1000 / 60.0;
        assertEquals(2 * v / 270 + (30 - v * v / 270) / v, e.machineS(), 1e-9);
    }

    @Test
    public void arcsAreSplitLikeGrbl() {
        // full circle r=10: segments = floor(0.5·2π·10 / sqrt(0.01·(20−0.01))) = floor(31.4159/0.44710) = 70
        Planner probe = new Planner(S, 13);
        assertEquals(0, probe.size());
        Estimate e = est("""
                G21 G90 G17
                G1 X10 Y0 F600
                G2 X10 Y0 I-10 J0
                """, null);
        double circle = 2 * Math.PI * 10 / 10.0; // 62.8 mm at 10 mm/s
        double arc = e.lineEndS()[2] - e.lineEndS()[1];
        assertTrue("arc " + arc, arc > circle && arc < circle * 1.05);
    }

    @Test
    public void toolChangesAreReportedNotCountedAsMachineTime() throws Exception {
        MachineProfile p = MachineProfile.fromJson("""
                {"safe_z_machine": -5, "tool_change_park": [-222, -440, -5],
                 "bitsetter": {"xy": [-4.2, -448.2], "start_z_machine": -5, "max_travel": 90, "fast_feed": 800,
                               "slow_feed": 100, "retract": 2},
                 "spindle": {"spin_down_s": 8}}
                """);
        Estimate e = est("""
                G21 G90
                (---- OP 01 | T2 | first | a ----)
                T2 M6
                G1 X10 F600
                M5
                T21 M6
                (---- OP 02 | T21 | second | b ----)
                G1 X20 F600
                M2
                """, p);
        assertEquals(2, e.toolChanges().size());
        Estimate.ToolChange first = e.toolChanges().get(0);
        assertNull(first.from());
        assertEquals(2, first.to());
        Estimate.ToolChange second = e.toolChanges().get(1);
        assertEquals(Integer.valueOf(2), second.from());
        assertEquals(21, second.to());
        assertEquals(e.lineEndS()[4], second.atS(), 1e-9);
        assertTrue("cycle includes the 8 s spin-down", second.bitsetterS() > 8 + 5);
        assertEquals(e.bitsetterS(), first.bitsetterS() + second.bitsetterS(), 1e-9);
        assertEquals(e.machineS() + e.bitsetterS(), e.totalS(), 1e-9);
        assertEquals(2, e.ops().size());
        Map<Integer, Double> perTool = new java.util.HashMap<>();
        e.tools().forEach(t -> perTool.put(t.number(), t.seconds()));
        assertTrue(perTool.containsKey(2) && perTool.containsKey(21));
        JsonObject j = e.toJson();
        assertEquals("grbl-planner", j.get("source").getAsString());
        assertEquals("T21", j.getAsJsonArray("tool_changes").get(1).getAsJsonObject().get("to").getAsString());
        assertTrue(j.getAsJsonArray("tool_changes").get(1).getAsJsonObject().get("operator_s").isJsonNull());
    }

    @Test
    public void controllerSettingsWinOverTheProfile() {
        MachineProfile p = new MachineProfile();
        MotionSettings fromProfile = MotionSettings.fromProfile(p, 14);
        assertEquals("profile", fromProfile.source());
        assertTrue(fromProfile.assumptions().size() >= 4);
        MotionSettings live = MotionSettings.fromController(Map.of("$110", 8000.0, "$111", 8000.0, "$112", 900.0,
                "$120", 400.0, "$121", 400.0, "$122", 200.0, "$11", 0.02), p, 14);
        assertEquals("controller", live.source());
        assertEquals(8000, live.maxRateMmMin()[0], 0);
        assertEquals(0.02, live.junctionDeviationMm(), 0);
        assertEquals(MotionSettings.DEFAULT_ARC_TOLERANCE, live.arcToleranceMm(), 0);
        MotionSettings partial = MotionSettings.fromController(Map.of("$110", 8000.0), p, 14);
        assertEquals("profile", partial.source());
    }
}
