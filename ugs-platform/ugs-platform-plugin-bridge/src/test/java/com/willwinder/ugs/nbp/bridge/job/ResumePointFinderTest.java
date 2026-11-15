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

import static com.willwinder.ugs.nbp.bridge.job.Programs.WCO;
import static com.willwinder.ugs.nbp.bridge.job.Programs.lineOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ResumePointFinderTest {
    private final JobModel run = JobModel.parse("run.nc", Programs.straightRun(), null);

    private JobState journal(JobModel job, int acked, int done, double[] workPos) {
        JobState j = JobState.start(job, "s", Instant.parse("2026-11-15T12:00:00Z"));
        j.stream = "stopped";
        j.lineAcked = acked;
        j.lineDone = done;
        j.wco = WCO.clone();
        if (workPos != null) {
            j.lastMpos = new double[]{workPos[0] + WCO[0], workPos[1] + WCO[1], workPos[2] + WCO[2]};
        }
        return j;
    }

    private int x(int i) {
        return lineOf(run, i == 1 ? "X1 F1000" : "X" + i);
    }

    @Test
    public void backoffCountsPlannerBlocks() {
        assertEquals(x(26), ResumePointFinder.backoffStart(run, x(40), PlannerModel.GRBL));
        PlannerModel small = new PlannerModel(4, 128, false, 0.05);
        assertEquals(x(37), ResumePointFinder.backoffStart(run, x(40), small));
        // fewer motion lines than planner blocks: everything might be pending
        assertEquals(1, ResumePointFinder.backoffStart(run, x(3), PlannerModel.GRBL));
        assertEquals(1, ResumePointFinder.backoffStart(run, 0, PlannerModel.GRBL));
    }

    @Test
    public void ackMeansSentBacksOverRxBufferToo() {
        int sent = ResumePointFinder.backoffStart(run, x(40), PlannerModel.GRBL.withAckMeansSent(true));
        assertTrue(sent < x(26));
        // "X40\n" .. are 4 bytes each: 32 lines fill 128 bytes, then 15 planner blocks
        assertEquals(x(40) - 32 - 14, sent);
    }

    @Test
    public void powerCutBacksUpPastTheBuffers() {
        ResumePointFinder.Result r = ResumePointFinder.find(run, journal(run, x(40), 0, null), PlannerModel.GRBL);
        assertEquals(x(26), r.earliestUnexecuted());
        assertEquals("buffer_backoff", r.method());
        assertTrue(r.notes().get(0).contains("power cut"));
    }

    @Test
    public void lineDoneTightensTheWindow() {
        ResumePointFinder.Result r = ResumePointFinder.find(run, journal(run, x(40), x(35), null), PlannerModel.GRBL);
        assertEquals(x(36), r.earliestUnexecuted());
    }

    @Test
    public void positionPicksTheRunningSegment() {
        ResumePointFinder.Result r = ResumePointFinder.find(run, journal(run, x(40), 0, new double[]{30.5, 0, -1}),
                PlannerModel.GRBL);
        assertEquals("position", r.method());
        assertEquals(x(31), r.earliestUnexecuted());
        assertEquals(0, r.matchDistanceMm(), 1e-9);
    }

    @Test
    public void positionAtAnEndpointPicksTheEarlierLine() {
        ResumePointFinder.Result r = ResumePointFinder.find(run, journal(run, x(40), 0, new double[]{30, 0, -1}),
                PlannerModel.GRBL);
        assertEquals(x(30), r.earliestUnexecuted());
    }

    @Test
    public void positionWithinTolerance() {
        ResumePointFinder.Result r = ResumePointFinder.find(run, journal(run, x(40), 0, new double[]{30.5, 0.04, -1}),
                PlannerModel.GRBL);
        assertEquals(x(31), r.earliestUnexecuted());
        r = ResumePointFinder.find(run, journal(run, x(40), 0, new double[]{30.5, 0.04, -1}),
                PlannerModel.GRBL.withTolerance(0.01));
        assertEquals("buffer_backoff", r.method());
    }

    @Test
    public void positionOffThePathFallsBack() {
        // a position on an already-done line (outside the window) is not trusted
        ResumePointFinder.Result r = ResumePointFinder.find(run, journal(run, x(40), 0, new double[]{5.5, 0, -1}),
                PlannerModel.GRBL);
        assertEquals("buffer_backoff", r.method());
        assertEquals(x(26), r.earliestUnexecuted());
        assertTrue(r.notes().get(0).contains("not on the path"));
    }

    @Test
    public void positionWithoutWcoIsIgnored() {
        JobState j = journal(run, x(40), 0, new double[]{30.5, 0, -1});
        j.wco = null;
        ResumePointFinder.Result r = ResumePointFinder.find(run, j, PlannerModel.GRBL);
        assertEquals("buffer_backoff", r.method());
    }

    @Test
    public void arcsAreMatchedOnTheArcNotTheChord() {
        JobModel job = JobModel.parse("two.nc", Programs.TWO_TOOLS, null);
        int g2 = lineOf(job, "G2 X40 Y30 I5 J0 F1143");
        int last = lineOf(job, "G0 Z5", 4);
        // CW from (30,30) around (35,30) to (40,30) passes (35,35)
        ResumePointFinder.Result r = ResumePointFinder.find(job, journal(job, last, 0, new double[]{35, 35, -1}),
                PlannerModel.GRBL);
        assertEquals("position", r.method());
        assertEquals(g2, r.earliestUnexecuted());
        // the chord midpoint (35,30) is not on the arc
        r = ResumePointFinder.find(job, journal(job, last, 0, new double[]{35, 30, -1}), PlannerModel.GRBL);
        assertEquals("buffer_backoff", r.method());
    }

    @Test
    public void inchProgramsCompareInMillimetres() {
        JobModel inch = JobModel.parse("inch.nc", "G20 G90\nG0 X0 Y0 Z0.2\nG1 Z-0.04 F10\nG1 X1 F20\nG1 X2\nG0 Z0.2\n", null);
        ResumePointFinder.Result r = ResumePointFinder.find(inch, journal(inch, 6, 0, new double[]{38.1, 0, -1.016}),
                PlannerModel.GRBL);
        assertEquals("position", r.method());
        assertEquals(5, r.earliestUnexecuted());
    }

    @Test
    public void completedJob() {
        JobState j = journal(run, run.lineCount(), run.lineCount(), null);
        j.stream = "completed";
        ResumePointFinder.Result r = ResumePointFinder.find(run, j, PlannerModel.GRBL);
        assertEquals(run.lineCount() + 1, r.earliestUnexecuted());
        assertEquals("completed", r.method());
    }
}
