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

import java.util.List;

import static com.willwinder.ugs.nbp.bridge.job.Programs.lineOf;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class JobModelTest {
    private final JobModel job = JobModel.parse("two.nc", Programs.TWO_TOOLS, null);

    @Test
    public void positionsAndModalState() {
        int x20 = lineOf(job, "X20 F1778");
        assertArrayEquals(new double[]{10, 10, -2}, job.startPosition(x20), 1e-9);
        assertArrayEquals(new double[]{20, 10, -2}, job.endPosition(x20), 1e-9);
        assertEquals(JobModel.Motion.LINEAR, job.motion(x20));
        assertFalse(job.hasExplicitMotionWord(x20));
        Modal m = job.modalAt(x20);
        assertEquals("G21", m.units());
        assertEquals("G90", m.distance());
        assertEquals("G1", m.motion());
        assertEquals(508, m.feed(), 1e-9);
        assertEquals("M3", m.spindle());
        assertEquals(18000, m.speed(), 1e-9);
        assertEquals(1778, job.modalAt(x20 + 1).feed(), 1e-9);
    }

    @Test
    public void g53LeavesWorkPositionUnknown() {
        int firstPark = lineOf(job, "G53 G0 Z-5.000", 1);
        assertTrue(job.isG53(firstPark));
        assertTrue(Double.isNaN(job.endPosition(firstPark)[2]));
        int secondPark = lineOf(job, "G53 G0 Z-5.000", 2);
        double[] after = job.endPosition(secondPark);
        assertEquals(10, after[0], 1e-9);
        assertEquals(20, after[1], 1e-9);
        assertTrue(Double.isNaN(after[2]));
    }

    @Test
    public void toolsAndToolChanges() {
        int m6a = lineOf(job, "T2 M6");
        int m6b = lineOf(job, "T21 M6");
        assertEquals(List.of(m6a, m6b), job.toolChangeLines());
        assertEquals(21, job.toolChangeTarget(m6b));
        assertEquals(-1, job.toolChangeTarget(m6b + 1));
        assertEquals(0, job.toolAt(m6a));
        assertEquals(2, job.toolAt(m6a + 1));
        assertEquals(2, job.toolAt(m6b));
        assertEquals(21, job.toolAt(m6b + 1));
        assertEquals("T02", job.toolId(2));
        assertEquals("T21", job.toolId(21));
        ToolInfo t2 = job.tools().get(2);
        assertEquals("2-flute downcut spiral 1/4 in, Whiteside RD2100", t2.name());
        assertEquals(508, t2.plungeFeed(), 1e-9);
        ToolInfo t21 = job.tools().get(21);
        assertEquals(381, t21.plungeFeed(), 1e-9);
        assertEquals(1143, t21.cutFeed(), 1e-9);
    }

    @Test
    public void headerOperations() {
        assertEquals(5.0, job.clearanceZ(), 1e-9);
        assertEquals("header", job.clearanceSource());
        List<Operation> ops = job.operations();
        assertEquals(2, ops.size());
        Operation pocket = ops.get(0);
        assertEquals("pocket", pocket.name());
        assertEquals("T02", pocket.tool());
        assertEquals(508, pocket.plungeFeed(), 1e-9);
        assertEquals(1778, pocket.cutFeed(), 1e-9);
        assertEquals(lineOf(job, "(---- OP 01 | T02 | pocket | a test pocket ----)"), pocket.startLine());
        assertEquals(lineOf(job, "M5", 1), pocket.endLine()); // stops before the FEEDS comment of the change block
        Operation finish = ops.get(1);
        assertEquals(21, finish.toolNumber());
        assertEquals(381, finish.plungeFeed(), 1e-9); // from the (FEEDS: ...) block of its M6
        assertEquals(job.lineCount(), finish.endLine());
        assertEquals(finish, job.findOperation("FINISH").orElseThrow());
        assertEquals(finish, job.findOperation("OP 02").orElseThrow());
        assertEquals(finish, job.findOperation("2").orElseThrow());
        assertTrue(job.findOperation("nope").isEmpty());
        assertEquals(pocket, job.operationAt(lineOf(job, "Y20")).orElseThrow());
        assertTrue(job.operationAt(lineOf(job, "T21 M6")).isEmpty());
        assertEquals(finish, job.operationAtOrAfter(lineOf(job, "T21 M6")).orElseThrow());
    }

    @Test
    public void safePoints() {
        int plunge = lineOf(job, "G1 Z-2 F508");
        int x20 = lineOf(job, "X20 F1778");
        int y20 = lineOf(job, "Y20");
        assertTrue(job.isSafePoint(plunge)); // starts at Z5
        assertFalse(job.isSafePoint(x20));
        assertFalse(job.isSafePoint(y20));
        assertEquals(plunge, job.previousSafePoint(y20));
        assertTrue(job.isSafePoint(lineOf(job, "M5", 1))); // after a G53 retract
        assertTrue(job.isSafePoint(1));
        assertEquals(0, job.previousSafePoint(0));
        for (int i = 1; i <= job.lineCount(); i++) {
            int sp = job.previousSafePoint(i);
            assertTrue(sp >= 1 && sp <= i && job.isSafePoint(sp));
        }
    }

    @Test
    public void arcs() {
        int g2 = lineOf(job, "G2 X40 Y30 I5 J0 F1143");
        assertEquals(JobModel.Motion.ARC_CW, job.motion(g2));
        assertTrue(job.hasExplicitMotionWord(g2));
        assertArrayEquals(new double[]{35, 30, -1, 17}, job.arcCenter(g2), 1e-9);
        int cont = lineOf(job, "X30 Y30 I-5 J0");
        assertEquals(JobModel.Motion.ARC_CW, job.motion(cont));
        assertFalse(job.hasExplicitMotionWord(cont));
    }

    @Test
    public void incrementalSectionsAreNotSafe() {
        JobModel j = JobModel.parse("g91.nc", """
                (CLEARANCE: Z+5)
                G21 G90
                G0 X0 Y0 Z5
                G91
                G0 X1
                G0 X1
                G90
                G0 X5
                """, null);
        assertEquals("G91", j.modalAt(5).distance());
        assertFalse(j.isSafePoint(5));
        assertFalse(j.isSafePoint(6));
        assertFalse(j.isSafePoint(7));
        assertTrue(j.isSafePoint(4));
        assertTrue(j.isSafePoint(8));
        assertArrayEquals(new double[]{2, 0, 5}, j.startPosition(7), 1e-9);
        assertEquals(4, j.previousSafePoint(7));
    }

    @Test
    public void fallbackSegmentsByToolChange() {
        JobModel j = JobModel.parse("plain.nc", """
                G21 G90
                M6 T3
                M3 S18000
                G0 Z10
                G0 X1 Y1
                G1 Z-3 F635
                G1 X5 F2032
                G0 Z10
                M6 T4
                G0 X2 Y2
                G1 Z-1 F300
                G0 Z10
                M2
                """, null);
        assertEquals("inferred_max_z", j.clearanceSource());
        assertEquals(10, j.clearanceZ(), 1e-9);
        List<Operation> ops = j.operations();
        assertEquals(2, ops.size());
        assertEquals(2, ops.get(0).startLine());
        assertEquals(8, ops.get(0).endLine());
        assertEquals(3, ops.get(0).toolNumber());
        assertEquals(635, ops.get(0).plungeFeed(), 1e-9);
        assertEquals("tool_change", ops.get(0).source());
        assertEquals(4, ops.get(1).toolNumber());
        assertEquals(300, ops.get(1).plungeFeed(), 1e-9);
        assertFalse(j.warnings().isEmpty());
    }

    @Test
    public void colonOpsTakeToolFromM6AndFeedsFromToolList() {
        JobModel j = JobModel.parse("colon.nc", """
                (CLEARANCE: rapids/retracts at Z+20.50 in this file's numbers [7.5 above)
                (  1. T01  3-wing surfacing bit 1 in    Whiteside 6210   LOADS AT ~min 1)
                (       orange label, 5 x 8 cm box   feed 2286 / plunge 254 mm/min)
                (OPERATIONS:)
                (   1. [T01] ~min   1  rough top)
                G21 G90 G94
                T1 M6
                G0 Z20.500
                S16000 M3
                (---- OP 1: rough top ----)
                G0 X4 Y24
                G1 Z14.5 F254
                G1 X29 F2286
                G0 Z20.5
                """, null);
        assertEquals(20.5, j.clearanceZ(), 1e-9);
        Operation op = j.operations().get(0);
        assertEquals("rough top", op.name());
        assertEquals("T01", op.tool());
        assertEquals(1, op.toolNumber());
        assertEquals(254, op.plungeFeed(), 1e-9);
        assertEquals(2286, op.cutFeed(), 1e-9);
        assertEquals("3-wing surfacing bit 1 in", j.tools().get(1).name());
    }

    @Test
    public void sidecarOverridesHeaders() {
        JobSidecar sc = JobSidecar.fromJson("""
                {"format": "ugs-bridge-job/1", "units": "mm", "clearance_z_work": 6.5,
                 "tools": [{"id": "T21", "type": "flat", "diameter": 3.175, "feed": 1000, "plunge": 300}],
                 "operations": [{"name": "everything", "tool": "T21", "lines": [22, 31]},
                                {"name": "bad", "tool": "T21", "lines": [40, 9999]}]}
                """);
        JobModel j = JobModel.parse("two.nc", Programs.TWO_TOOLS, sc);
        assertEquals(6.5, j.clearanceZ(), 1e-9);
        assertEquals("sidecar", j.clearanceSource());
        assertEquals(1, j.operations().size());
        Operation op = j.operations().get(0);
        assertEquals("everything", op.name());
        assertEquals(300, op.plungeFeed(), 1e-9);
        assertEquals("sidecar", op.source());
        assertTrue(j.warnings().stream().anyMatch(w -> w.contains("bad")));
    }

    @Test
    public void stripComments() {
        assertEquals("G0 X1  Y2", JobModel.stripComments("G0 X1 (a (nested) one) Y2 ; tail"));
        assertEquals("", JobModel.stripComments("(   T3    6.350 mm (0.2500 in)   feed  2032 mm/min)"));
        assertEquals("", JobModel.stripComments("%"));
    }

    @Test
    public void crlfAndShaAndEmptyOps() {
        JobModel j = JobModel.parse("crlf.nc", "G21\r\nG0 X1 Y1 Z1\r\n", null);
        assertEquals(2, j.lineCount());
        assertEquals("G21", j.line(1));
        assertEquals(64, j.sha256().length());
        assertNull(j.path());
    }

    @Test
    public void positionResettingCodesWarn() {
        JobModel j = JobModel.parse("g92.nc", "G21 G90\nG0 X1 Y1 Z5\nG92 X0 Y0\nG0 X2\n", null);
        assertTrue(j.resetsPosition(3));
        assertTrue(Double.isNaN(j.startPosition(4)[0]));
        assertTrue(j.warnings().stream().anyMatch(w -> w.contains("G92")));
    }
}
