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

import org.junit.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.willwinder.ugs.nbp.bridge.job.Programs.WCO;
import static com.willwinder.ugs.nbp.bridge.job.Programs.lineOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ResumePlannerTest {
    private final JobModel job = JobModel.parse("two.nc", Programs.TWO_TOOLS, null);
    private final MachineProfile profile = Programs.profile();

    private JobState journal(String tool) {
        JobState j = JobState.start(job, "s", Instant.parse("2026-11-15T12:00:00Z"));
        j.stream = "stopped";
        j.tool = tool;
        j.wco = WCO.clone();
        j.bitsetterRef = 31.25;
        return j;
    }

    private ResumeContext ctx(String tool) {
        return ResumeContext.homedWith(WCO.clone(), tool);
    }

    @Test
    public void midContourPreambleInOrder() {
        int y20 = lineOf(job, "Y20");
        ResumeContext c = ctx("T02");
        c.midContour = true;
        ResumePlan p = ResumePlanner.plan(job, profile, journal("T02"), c, ResumeTarget.line(y20));
        assertEquals(y20, p.resumeLine);
        assertFalse(p.safePoint);
        assertEquals(List.of(ResumePlan.CONFIRM_MID_CONTOUR), p.requires);
        assertEquals(List.of(
                "G21 G53 G0 Z-5.000",
                "G21 G90 G17 G94 G54",
                "G0 X20.000 Y10.000",
                "G0 Z5.000",
                "S18000 M3",
                "G4 P3",
                "G1 Z-2.000 F508",
                "G1 F1778"), p.preamble);
        assertEquals("pocket", p.operation);
        assertEquals("T02", p.tool);
        assertFalse(p.ready);
    }

    @Test
    public void snapsToPreviousSafePoint() {
        int y20 = lineOf(job, "Y20");
        int plunge = lineOf(job, "G1 Z-2 F508");
        ResumePlan p = ResumePlanner.plan(job, profile, journal("T02"), ctx("T02"), ResumeTarget.line(y20));
        assertEquals(plunge, p.resumeLine);
        assertEquals(Integer.valueOf(y20), p.candidateLine);
        assertTrue(p.safePoint);
        assertTrue(p.requires.isEmpty());
        assertTrue(p.toText(), p.ready);
        assertEquals(List.of(
                "G21 G53 G0 Z-5.000",
                "G21 G90 G17 G94 G54",
                "G0 X10.000 Y10.000",
                "G0 Z5.000",
                "S18000 M3",
                "G4 P3",
                "G0"), p.preamble);
        assertTrue(p.warnings.stream().anyMatch(w -> w.contains("instead of " + y20)));
    }

    @Test
    public void operationInAnotherToolRequiresChangeAndMeasure() {
        ResumePlan p = ResumePlanner.plan(job, profile, journal("T02"), ctx("T02"), ResumeTarget.operation("finish"));
        assertEquals(lineOf(job, "(---- OP 02 | T21 | finish | a test finish ----)"), p.resumeLine);
        assertEquals("T21", p.tool);
        assertEquals(List.of(ResumePlan.TOOL_CHANGE, ResumePlan.TOOL_MEASURE), p.requires);
        assertFalse(p.check("tool").ok);
        assertTrue(p.check("bitsetter_ref").ok);
        assertTrue(p.check("origin").ok); // XY only: Z is set by the measurement
        assertEquals(381, p.plungeFeed, 1e-9);
        assertNull(p.toolChangeInStream);
    }

    @Test
    public void wcoZDifferenceIsIgnoredWhenTheToolIsMeasured() {
        ResumeContext c = ctx("T02");
        c.currentWco = new double[]{WCO[0], WCO[1], WCO[2] + 3};
        ResumePlan p = ResumePlanner.plan(job, profile, journal("T02"), c, ResumeTarget.operation("finish"));
        assertFalse(p.requires.contains(ResumePlan.RESTORE_ORIGIN));
        p = ResumePlanner.plan(job, profile, journal("T02"), c, ResumeTarget.operation("pocket"));
        assertTrue(p.requires.contains(ResumePlan.RESTORE_ORIGIN));
    }

    @Test
    public void unknownToolRequiresChange() {
        ResumeContext c = ctx(null);
        ResumePlan p = ResumePlanner.plan(job, profile, null, c, ResumeTarget.operation("pocket"));
        assertTrue(p.requires.contains(ResumePlan.TOOL_CHANGE));
        assertTrue(p.check("tool").detail.contains("unknown"));
        // no journal: origin taken from the controller, with a warning
        assertTrue(p.check("origin").ok);
        assertTrue(p.warnings.stream().anyMatch(w -> w.contains("No journal origin")));
    }

    @Test
    public void powerLossRequiresMeasureAndReference() {
        ResumeContext c = ctx("T02");
        c.powerLoss = true;
        c.homed = false;
        JobState j = journal("T02");
        j.bitsetterRef = null;
        ResumePlan p = ResumePlanner.plan(job, profile, j, c, ResumeTarget.operation("pocket"));
        assertEquals(List.of(ResumePlan.HOME, ResumePlan.TOOL_MEASURE), p.requires);
        assertFalse(p.check("tool_length").ok);
        assertFalse(p.check("bitsetter_ref").ok);
        assertFalse(p.check("homed").ok);
        assertFalse(p.ready);
    }

    @Test
    public void wcoMismatchRequiresRestoreOrigin() {
        ResumeContext c = ctx("T02");
        c.currentWco = new double[]{WCO[0] + 0.02, WCO[1], WCO[2]};
        ResumePlan p = ResumePlanner.plan(job, profile, journal("T02"), c, ResumeTarget.operation("pocket"));
        assertEquals(List.of(ResumePlan.RESTORE_ORIGIN), p.requires);
        c.currentWco = null;
        p = ResumePlanner.plan(job, profile, journal("T02"), c, ResumeTarget.operation("pocket"));
        assertEquals(List.of(ResumePlan.RESTORE_ORIGIN), p.requires);
        c.currentWco = new double[]{WCO[0] + 0.005, WCO[1], WCO[2]};
        p = ResumePlanner.plan(job, profile, journal("T02"), c, ResumeTarget.operation("pocket"));
        assertTrue(p.requires.isEmpty());
    }

    @Test
    public void resumeOnAnM6LineBacksUpBeforeTheChange() {
        int m6 = lineOf(job, "T21 M6");
        ResumePlan p = ResumePlanner.plan(job, profile, journal("T02"), ctx("T02"), ResumeTarget.line(m6));
        assertEquals(m6 - 1, p.resumeLine);
        assertEquals(Integer.valueOf(m6), p.toolChangeInStream);
        assertEquals("T21", p.tool);
        assertEquals("finish", p.operation);
        assertFalse(p.requires.contains(ResumePlan.TOOL_CHANGE));
        // spindle is off and Z is at the G53 park: no M3, no Z moves
        assertEquals(List.of("G21 G53 G0 Z-5.000", "G21 G90 G17 G94 G54", "G0 X10.000 Y20.000", "G0 F1778"), p.preamble);
    }

    @Test
    public void incrementalSectionBacksUp() {
        JobModel g91 = JobModel.parse("g91.nc", """
                (CLEARANCE: Z+5)
                G21 G90
                G0 X0 Y0 Z5
                G91
                G0 X1
                G0 X1
                G90
                G0 X5
                """, null);
        ResumeContext c = ctx(null);
        c.midContour = true;
        ResumePlan p = ResumePlanner.plan(g91, profile, null, c, ResumeTarget.line(6));
        assertEquals(4, p.resumeLine);
        assertTrue(p.warnings.stream().anyMatch(w -> w.contains("G91")));
        assertTrue(p.preamble.contains("G21 G90 G17 G94 G54"));
    }

    @Test
    public void modalArcBacksUpToItsG2Line() {
        int g2 = lineOf(job, "G2 X40 Y30 I5 J0 F1143");
        int cont = lineOf(job, "X30 Y30 I-5 J0");
        ResumeContext c = ctx("T21");
        c.midContour = true;
        ResumePlan p = ResumePlanner.plan(job, profile, journal("T21"), c, ResumeTarget.line(cont));
        assertEquals(g2, p.resumeLine);
        assertTrue(p.warnings.stream().anyMatch(w -> w.contains("modal arc")));
        assertTrue(p.warnings.stream().anyMatch(w -> w.contains("is an arc")));
        assertTrue(p.preamble.contains("G0 X30.000 Y30.000"));
        assertTrue(p.preamble.contains("G1 Z-1.000 F381"));
        assertEquals("G1 F381", p.preamble.get(p.preamble.size() - 1));
    }

    @Test
    public void envelopeViolationIsReported() {
        double[] off = {5, -350, -60};
        JobState j = journal("T02");
        j.wco = off;
        ResumeContext c = ctx("T02");
        c.currentWco = off.clone();
        ResumePlan p = ResumePlanner.plan(job, profile, j, c, ResumeTarget.operation("pocket"));
        assertFalse(p.check("envelope").ok);
        assertTrue(p.check("envelope").detail.startsWith("X"));
        assertFalse(p.ready);
    }

    @Test
    public void inchFileConvertsPlungeFeed() {
        JobModel inch = JobModel.parse("inch.nc", """
                (CLEARANCE: Z+0.2)
                (TOOL [FIRST]: T02 bit   feed 1778 / plunge 508 mm/min)
                G20 G90 G94 G17 G54
                T2 M6
                S18000 M3
                (---- OP 01 | T02 | inch | x ----)
                G0 X1 Y1
                G0 Z0.2
                G1 Z-0.1 F20
                G1 X2 F70
                G1 X3
                G0 Z0.2
                """, null);
        ResumeContext c = ctx("T02");
        c.midContour = true;
        ResumePlan p = ResumePlanner.plan(inch, profile, null, c, ResumeTarget.line(11));
        assertEquals(List.of(
                "G21 G53 G0 Z-5.000",
                "G20 G90 G17 G94 G54",
                "G0 X2.000 Y1.000",
                "G0 Z0.200",
                "S18000 M3",
                "G4 P3",
                "G1 Z-0.100 F20",
                "G1 F70"), p.preamble);
    }

    @Test
    public void stoppedUsesTheJournal() {
        JobState j = journal("T02");
        int y20 = lineOf(job, "Y20");
        j.lineAcked = y20;
        j.lineDone = 0;
        j.lastMpos = new double[]{20 + WCO[0], 15 + WCO[1], -2 + WCO[2]};
        ResumePlan p = ResumePlanner.plan(job, profile, j, ctx("T02"), ResumeTarget.stopped());
        assertEquals("position", p.finderMethod);
        assertEquals(Integer.valueOf(y20), p.candidateLine);
        assertEquals(lineOf(job, "G1 Z-2 F508"), p.resumeLine);
        assertEquals(Map.of("stopped", true), p.requested);
        assertTrue(p.ready);
    }

    @Test
    public void badTargets() {
        expect(IllegalArgumentException.class, () -> ResumePlanner.plan(job, profile, null, ctx("T02"), ResumeTarget.line(0)));
        expect(IllegalArgumentException.class, () -> ResumePlanner.plan(job, profile, null, ctx("T02"), ResumeTarget.line(job.lineCount() + 1)));
        expect(IllegalArgumentException.class, () -> ResumePlanner.plan(job, profile, null, ctx("T02"), ResumeTarget.operation("nope")));
        expect(IllegalStateException.class, () -> ResumePlanner.plan(job, profile, null, ctx("T02"), ResumeTarget.stopped()));
        JobState done = journal("T21");
        done.stream = "completed";
        done.lineAcked = done.lineDone = job.lineCount();
        expect(IllegalStateException.class, () -> ResumePlanner.plan(job, profile, done, ctx("T21"), ResumeTarget.stopped()));
    }

    @Test
    public void journalForAnotherFileIsIgnored() {
        JobState j = journal("T02");
        j.file.sha256 = "deadbeef";
        ResumePlan p = ResumePlanner.plan(job, profile, j, ctx("T02"), ResumeTarget.operation("pocket"));
        assertFalse(p.check("journal_file").ok);
        assertFalse(p.ready);
        expect(IllegalStateException.class, () -> ResumePlanner.plan(job, profile, j, ctx("T02"), ResumeTarget.stopped()));
    }

    @Test
    public void planIdIsDeterministic() {
        ResumePlan a = ResumePlanner.plan(job, profile, journal("T02"), ctx("T02"), ResumeTarget.operation("pocket"));
        ResumePlan b = ResumePlanner.plan(job, profile, journal("T02"), ctx("T02"), ResumeTarget.operation("pocket"));
        ResumePlan c = ResumePlanner.plan(job, profile, journal("T02"), ctx("T02"), ResumeTarget.operation("finish"));
        assertEquals(a.planId, b.planId);
        assertNotEquals(a.planId, c.planId);
        assertTrue(a.planId.startsWith("rp-"));
    }

    @Test
    public void targetJson() {
        assertEquals(ResumeTarget.line(5), ResumeTarget.fromJson(Map.of("line", 5.0)));
        assertEquals(ResumeTarget.operation("x"), ResumeTarget.fromJson(Map.of("operation", "x")));
        assertEquals(ResumeTarget.stopped(), ResumeTarget.fromJson(Map.of("stopped", true)));
        expect(IllegalArgumentException.class, () -> ResumeTarget.fromJson(Map.of()));
    }

    private static void expect(Class<? extends Throwable> type, Runnable r) {
        try {
            r.run();
            fail("expected " + type.getSimpleName());
        } catch (Throwable t) {
            if (!type.isInstance(t)) {
                throw new AssertionError("expected " + type.getSimpleName() + " but got " + t, t);
            }
        }
    }
}
