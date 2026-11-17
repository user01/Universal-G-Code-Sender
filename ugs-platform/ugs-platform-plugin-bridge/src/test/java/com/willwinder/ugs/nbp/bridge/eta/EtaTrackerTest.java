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
package com.willwinder.ugs.nbp.bridge.eta;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.willwinder.ugs.nbp.bridge.job.MachineProfile;
import com.willwinder.ugs.nbp.bridge.job.estimate.Estimate;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Progress, pauses, ratio and tool changes from synthetic session-log records. */
public class EtaTrackerTest {
    /** Two tools; every cut is 100 mm at 10 mm/s (≈ 10.02 s). Capture numbers file lines one higher. */
    static final String PROGRAM = """
            G21 G90
            (---- OP 01 | T2 | rough | first ----)
            T2 M6
            G1 X100 F600
            G1 X0
            M5
            T21 M6
            (---- OP 02 | T21 | finish | second ----)
            G1 X100 F600
            G1 X0
            M2
            """;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    final AtomicLong nanos = new AtomicLong(5_000_000_000L);
    final Clock wall = Clock.fixed(Instant.parse("2026-11-16T22:00:00Z"), ZoneOffset.ofHours(-5));
    EstimateCache cache;
    EtaTracker eta;
    Path file;
    int cmd;

    @Before
    public void setUp() throws Exception {
        MachineProfile p = MachineProfile.fromJson("""
                {"safe_z_machine": -5, "tool_change_park": [-222, -440, -5], "planner_blocks": 14,
                 "motion": {"max_rate_mm_min": [10000, 10000, 1000], "accel_mm_s2": [500, 500, 270],
                            "junction_deviation_mm": 0.02, "arc_tolerance_mm": 0.01},
                 "bitsetter": {"xy": [-4.2, -448.2], "start_z_machine": -5, "max_travel": 90, "fast_feed": 800,
                               "slow_feed": 100, "retract": 2},
                 "spindle": {"spin_down_s": 8}}
                """);
        cache = new EstimateCache(() -> p);
        eta = new EtaTracker(cache, nanos::get, wall);
        file = tmp.newFile("two.nc").toPath();
        Files.writeString(file, PROGRAM);
    }

    @After
    public void tearDown() {
        cache.close();
    }

    void rec(String kind, String data) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        o.add("data", JsonParser.parseString(data).getAsJsonObject());
        eta.onRecord(o);
    }

    void advance(double s) {
        nanos.addAndGet((long) (s * 1e9));
    }

    /** UGS sends file line {@code line} and the controller answers ok. */
    void ack(int line) {
        String id = "u-" + (++cmd);
        rec("tx", "{\"line\":\"x\",\"cmd_id\":\"" + id + "\",\"file_line\":" + (line + 1) + "}");
        rec("rx", "{\"line\":\"ok\",\"cmd_id\":\"" + id + "\"}");
    }

    Estimate start() {
        rec("event", "{\"type\":\"stream\",\"event\":\"started\"}");
        rec("file", "{\"action\":\"stream_start\",\"path\":\"" + file + "\"}");
        Estimate e = cache.get(file, null, eta.controller(), 10_000).orElseThrow();
        assertNotNull(eta.estimate());
        return e;
    }

    @Test
    public void noStreamNoSnapshot() {
        assertNull(eta.snapshot());
    }

    @Test
    public void settingsAndPlannerDepthComeFromTheController() {
        rec("rx", "{\"line\":\"$110=8000.000\"}");
        rec("rx", "{\"line\":\"$120=400.000\"}");
        rec("rx", "{\"line\":\"[OPT:VNPR,14,128]\"}");
        EstimateCache.Controller c = eta.controller();
        assertEquals(8000.0, c.settings().get("$110"), 0);
        assertEquals(Integer.valueOf(14), c.plannerBlocks());
    }

    @Test
    public void progressPausesRatioAndToolChanges() {
        Estimate e = start();
        assertEquals(2, e.toolChanges().size());
        // first tool change at line 3: interceptor 40 s (excluded from machine time)
        rec("event", "{\"type\":\"interceptor\",\"state\":\"waiting_for_user\",\"file_line\":4}");
        advance(40);
        rec("event", "{\"type\":\"interceptor\",\"state\":\"inactive\"}");
        for (int l = 1; l <= 5; l++) {
            ack(l);
        }
        rec("status", "{\"state\":\"Run\"}");
        // still in the planner: nothing done yet
        EtaTracker.Snapshot s = eta.snapshot();
        assertEquals(5, s.ackedLine());
        assertEquals(0, s.doneLine());
        assertEquals(40, s.pausedS(), 1e-6);
        // the controller drains: lines 4 and 5 ran in 30 s instead of ≈ 20 s
        advance(30);
        rec("status", "{\"state\":\"Idle\"}");
        rec("event", "{\"type\":\"interceptor\",\"state\":\"waiting_for_user\",\"file_line\":8}");
        s = eta.snapshot();
        assertEquals(5, s.doneLine());
        double doneEst = e.atLine(5);
        assertEquals(doneEst, s.doneEstS(), 1e-9);
        assertTrue("two cuts ≈ 20 s, got " + doneEst, doneEst > 20 && doneEst < 20.2);
        assertEquals(30, s.machineS(), 1e-6);
        assertEquals(30 / doneEst, s.ratio(), 1e-9);
        assertTrue(s.waitingForToolChange());
        assertEquals("T21", s.currentTool());
        assertEquals(0, s.toolChangesRemaining()); // the change in progress isn't "remaining"
        assertNull(s.nextToolChange());
        double remaining = e.machineS() - doneEst;
        assertEquals(remaining, s.machineRemainingS(), 1e-9);
        assertEquals(remaining * 30 / doneEst, s.adjustedRemainingS(), 1e-6);
        assertEquals("2026-11-16T17:00:" + String.format("%02d", Math.round(remaining * 30 / doneEst)) + "-05:00",
                s.predictedFinish());
        assertEquals("finish", s.currentOp().name());
        JsonObject j = s.toJson();
        assertEquals(40, j.get("paused_s").getAsDouble(), 1e-9);
        assertTrue(j.get("next_tool_change").isJsonNull());
    }

    @Test
    public void nextToolChangeAheadAndHolds() {
        Estimate e = start();
        ack(1);
        ack(2);
        rec("status", "{\"state\":\"Idle\"}");
        EtaTracker.Snapshot s = eta.snapshot();
        assertEquals(2, s.toolChangesRemaining());
        assertEquals(3, s.nextToolChange().line());
        double ahead = e.toolChanges().get(0).bitsetterS() + e.toolChanges().get(1).bitsetterS();
        assertEquals(e.machineS() + ahead, s.adjustedRemainingS(), 1e-6);
        // feed hold is paused time
        rec("status", "{\"state\":\"Hold:0\"}");
        advance(12);
        rec("status", "{\"state\":\"Run\"}");
        assertEquals(12, eta.snapshot().pausedS(), 1e-6);
    }

    @Test
    public void completionFreezesTheNumbers() {
        start();
        advance(50);
        rec("event", "{\"type\":\"stream\",\"event\":\"complete\"}");
        advance(100);
        EtaTracker.Snapshot s = eta.snapshot();
        assertFalse(s.active());
        assertEquals("completed", s.result());
        assertEquals(50, s.elapsedS(), 1e-6);
        assertEquals(0, s.adjustedRemainingS(), 1e-9);
        assertNull(s.predictedFinish());
    }

    @Test
    public void ratioIsClamped() {
        start();
        for (int l = 1; l <= 5; l++) {
            ack(l);
        }
        rec("status", "{\"state\":\"Idle\"}");
        advance(1000);
        assertEquals(EtaTracker.RATIO_MAX, eta.snapshot().ratio(), 0);
    }
}
