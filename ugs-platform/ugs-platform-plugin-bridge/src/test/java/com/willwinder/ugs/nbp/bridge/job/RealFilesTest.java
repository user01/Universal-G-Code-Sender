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

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Tests against the operator's real files in bridge/testdata/nc (skipped when absent). */
public class RealFilesTest {
    private static final String DR_RYAN = "dr_ryan_complete_T02_then_T21.nc";
    private static JobModel drRyan;
    private static MachineProfile profile;

    @BeforeClass
    public static void load() throws IOException {
        profile = TestFiles.profile();
        Path p = TestFiles.repoRoot().resolve("bridge/testdata/nc").resolve(DR_RYAN);
        if (Files.isRegularFile(p)) {
            drRyan = JobModel.load(p);
        }
    }

    private static JobModel drRyan() {
        assumeTrue("missing " + DR_RYAN, drRyan != null);
        return drRyan;
    }

    @Test
    public void drRyanStructure() {
        JobModel job = drRyan();
        assertEquals(26781, job.lineCount());
        assertEquals(5.0, job.clearanceZ(), 1e-9);
        assertEquals("header", job.clearanceSource());
        List<Operation> ops = job.operations();
        assertEquals(9, ops.size());
        assertEquals("face_pad", ops.get(0).name());
        assertEquals("clear_hi", ops.get(4).name());
        assertEquals("T21", ops.get(4).tool());
        for (int i = 0; i < 4; i++) {
            assertEquals(2, ops.get(i).toolNumber());
            assertEquals(508.0, ops.get(i).plungeFeed(), 1e-9);
            assertEquals(1778.0, ops.get(i).cutFeed(), 1e-9);
        }
        for (int i = 4; i < 9; i++) {
            assertEquals(21, ops.get(i).toolNumber());
            assertEquals(381.0, ops.get(i).plungeFeed(), 1e-9);
            assertEquals(1143.0, ops.get(i).cutFeed(), 1e-9);
        }
        assertEquals(List.of(48, 17646), job.toolChangeLines());
        assertEquals(21, job.toolChangeTarget(17646));
        assertEquals(2, job.toolAt(17646));
        assertEquals(21, job.toolAt(17647));
        // op 04 ends before the tool-change block; op 05 starts at its header
        assertTrue(ops.get(3).endLine() < 17646);
        assertEquals(17651, ops.get(4).startLine());
        assertEquals(26781, ops.get(8).endLine());
        assertEquals("Whiteside RU1600", job.tools().get(21).name().replaceAll(".*, ", ""));
        assertEquals(381.0, job.tools().get(21).plungeFeed(), 1e-9);
        assertTrue(job.warnings().toString(), job.warnings().isEmpty());
    }

    @Test
    public void drRyanSafePointsNeverBelowClearance() {
        JobModel job = drRyan();
        for (int line = 1; line <= job.lineCount(); line += 37) {
            int sp = job.previousSafePoint(line);
            assertTrue(sp >= 1 && sp <= line);
            double z = job.startPosition(sp)[2];
            assertTrue("line " + line + " -> " + sp + " z " + z, Double.isNaN(z) || z >= job.clearanceZ() - 1e-6);
        }
    }

    @Test
    public void drRyanResumeAtT21OperationRequiresToolChange() {
        JobModel job = drRyan();
        JobState journal = JobState.start(job, "s1", java.time.Instant.parse("2026-11-15T12:00:00Z"));
        journal.tool = "T02";
        journal.wco = new double[]{-300, -350, -60};
        journal.bitsetterRef = 30.0;
        ResumeContext ctx = ResumeContext.homedWith(new double[]{-300, -350, -60}, "T02");
        ResumePlan plan = ResumePlanner.plan(job, profile, journal, ctx, ResumeTarget.operation("finish_top"));
        Operation op = job.findOperation("finish_top").orElseThrow();
        assertEquals(op.startLine(), plan.resumeLine);
        assertTrue(plan.safePoint);
        assertEquals("T21", plan.tool);
        assertEquals("finish_top", plan.operation);
        assertTrue(plan.requires.contains(ResumePlan.TOOL_CHANGE));
        assertTrue(plan.requires.contains(ResumePlan.TOOL_MEASURE));
        assertFalse(plan.requires.contains(ResumePlan.RESTORE_ORIGIN)); // XY matches, Z is set by the measurement
        assertTrue(plan.preamble.get(0).contains("G53 G0 Z-5.000"));
        assertEquals(381.0, plan.plungeFeed, 1e-9);
    }

    @Test
    public void drRyanMidOpResumePlungesAtT21PlungeFeed() {
        JobModel job = drRyan();
        Operation op = job.findOperation("clear_lo").orElseThrow();
        int mid = (op.startLine() + op.endLine()) / 2;
        JobState journal = JobState.start(job, "s1", java.time.Instant.parse("2026-11-15T12:00:00Z"));
        journal.tool = "T21";
        journal.wco = new double[]{-300, -350, -60};
        ResumeContext ctx = ResumeContext.homedWith(new double[]{-300, -350, -60}, "T21");
        ResumePlan plan = ResumePlanner.plan(job, profile, journal, ctx, ResumeTarget.line(mid));
        assertTrue(plan.resumeLine <= mid && plan.resumeLine >= op.startLine());
        assertTrue(plan.safePoint);
        assertTrue(plan.requires.toString(), plan.requires.isEmpty());
        assertTrue(plan.toText(), plan.ready);
        assertEquals("G21 G53 G0 Z-5.000", plan.preamble.get(0));
        assertEquals("G21 G90 G17 G94 G54", plan.preamble.get(1));
        double z = job.startPosition(plan.resumeLine)[2];
        if (!Double.isNaN(z) && z < 5) {
            assertTrue(plan.preamble.stream().anyMatch(l -> l.startsWith("G1 Z") && l.endsWith("F381")));
        }
    }

    @Test
    public void allRealFilesParse() throws IOException {
        Path dir = TestFiles.repoRoot().resolve("bridge/testdata/nc");
        assumeTrue(Files.isDirectory(dir));
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".nc")).sorted().toList()) {
                JobModel job = JobModel.load(f);
                assertFalse(f.toString(), job.operations().isEmpty());
                for (Operation op : job.operations()) {
                    assertTrue(f + " " + op, op.startLine() <= op.endLine());
                    assertTrue(f + " " + op, job.isSafePoint(op.startLine()));
                }
                // every op start resumes without exceptions
                for (Operation op : job.operations()) {
                    ResumeContext ctx = ResumeContext.homedWith(new double[]{-300, -350, -60}, op.tool());
                    ResumePlan plan = ResumePlanner.plan(job, profile, null, ctx, ResumeTarget.operation("OP " + op.index()));
                    assertNotNull(plan.planId);
                    assertTrue(Json.toJson(plan).contains("\"resume_line\"")); // no NaN in the JSON
                    assertEquals(op.startLine(), plan.resumeLine);
                }
                for (int line = 1; line <= job.lineCount(); line += 97) {
                    int sp = job.previousSafePoint(line);
                    double z = job.startPosition(sp)[2];
                    assertTrue(f + " line " + line, job.isOperationStart(sp) || Double.isNaN(z) || z >= job.clearanceZ() - 1e-6);
                }
            }
        }
    }

    @Test
    public void step20HasTwentyOneColonOps() throws IOException {
        JobModel job = JobModel.load(TestFiles.realNc("step20_carve_06_Erik_Langenborg_v0.12.nc"));
        assertEquals(21, job.operations().size());
        assertEquals(20.5, job.clearanceZ(), 1e-9);
        Operation first = job.operations().get(0);
        assertEquals(1, first.toolNumber());
        assertEquals(254.0, first.plungeFeed(), 1e-9);
        Operation last = job.operations().get(20);
        assertEquals(22, last.toolNumber());
        assertEquals(254.0, last.plungeFeed(), 1e-9);
        assertEquals(5, job.toolChangeLines().size());
    }

    @Test
    public void chelseaFallsBackToFreecadOps() throws IOException {
        JobModel job = JobModel.load(TestFiles.realNc("chelsea_nameplate.nc"));
        assertEquals("inferred_max_z", job.clearanceSource());
        assertEquals(10.0, job.clearanceZ(), 1e-9);
        List<Operation> ops = job.operations();
        assertEquals("Op1_Rough_T03", ops.get(0).name());
        assertEquals(3, ops.get(0).toolNumber());
        assertEquals(4, ops.get(ops.size() - 1).toolNumber());
        assertEquals(635.0, ops.get(0).plungeFeed(), 1e-9);
    }

    @Test
    public void drRyanStoppedResumesAreConservativeAndSafe() {
        JobModel job = drRyan();
        double[] wco = {-300, -350, -60};
        for (int acked = 500; acked < job.lineCount(); acked += 1499) {
            for (boolean withPosition : new boolean[]{false, true}) {
                JobState j = JobState.start(job, "s", java.time.Instant.parse("2026-11-15T12:00:00Z"));
                j.stream = withPosition ? "stopped" : "crashed";
                j.lineAcked = acked;
                j.wco = wco;
                j.tool = job.toolId(job.toolAt(acked));
                j.bitsetterRef = 31.0;
                int running = acked - 5;
                if (withPosition) {
                    double[] e = job.endPosition(running);
                    double[] s0 = job.startPosition(running);
                    // halfway along the running line
                    j.lastMpos = new double[]{(e[0] + s0[0]) / 2 + wco[0], (e[1] + s0[1]) / 2 + wco[1], (e[2] + s0[2]) / 2 + wco[2]};
                    if (Double.isNaN(j.lastMpos[0]) || Double.isNaN(j.lastMpos[2])) {
                        continue;
                    }
                }
                ResumeContext ctx = ResumeContext.homedWith(wco, j.tool);
                ctx.powerLoss = !withPosition;
                ResumePointFinder.Result r = ResumePointFinder.find(job, j, PlannerModel.GRBL);
                ResumePlan plan = ResumePlanner.plan(job, profile, j, ctx, ResumeTarget.stopped());
                assertTrue(r.earliestUnexecuted() <= acked + 1);
                if (withPosition && job.motion(running) != JobModel.Motion.NONE) {
                    assertTrue("acked " + acked + " " + r, r.earliestUnexecuted() <= running);
                }
                assertTrue(plan.resumeLine <= r.earliestUnexecuted());
                assertTrue(plan.safePoint);
                double z = job.startPosition(plan.resumeLine)[2];
                assertTrue(Double.isNaN(z) || z >= job.clearanceZ() - 1e-6);
                assertEquals("G21 G53 G0 Z-5.000", plan.preamble.get(0));
                if (!withPosition) {
                    assertTrue(plan.requires.contains(ResumePlan.TOOL_MEASURE));
                }
            }
        }
    }
}
