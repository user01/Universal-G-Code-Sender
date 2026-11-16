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
import com.willwinder.ugs.nbp.bridge.job.JobModel;
import com.willwinder.ugs.nbp.bridge.job.JobState;
import com.willwinder.ugs.nbp.bridge.job.PlannerModel;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class JournalTrackerTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final OriginTrackerTest.MutableClock clock = new OriginTrackerTest.MutableClock();
    private final Fixtures.Recs recs = new Fixtures.Recs();

    /** 40 feed moves (lines "X1".."X40") so the planner window matters. */
    private static String straightRun() {
        StringBuilder sb = new StringBuilder("(CLEARANCE: Z+5)\nG21 G90 G17 G94 G54\nT1 M6\nS10000 M3\nG0 X0 Y0\nG0 Z5\nG1 Z-1 F100\n");
        for (int i = 1; i <= 40; i++) {
            sb.append("X").append(i).append(i == 1 ? " F1000" : "").append('\n');
        }
        return sb.append("G0 Z5\nM5\n").toString();
    }

    @Test
    public void runningJournalTracksAcksConservativeDoneAndTransitions() throws Exception {
        JobModel job = JobModel.parse("run.nc", straightRun(), null);
        Path session = tmp.newFolder("session").toPath();
        JournalTracker j = new JournalTracker(recs, clock, 1000, PlannerModel.GRBL);
        j.start(job, session, "s1", new double[]{-300, -350, -60.00000000001}, new double[]{-1, -1, -1}, null, 8.520000000003);
        Path file = session.resolve(JobState.fileName(job.sha256()));
        assertEquals(file, j.path());
        JobState s0 = JobState.read(file);
        assertEquals("running", s0.stream);
        assertEquals(-60.0, s0.wco[2], 0);
        assertEquals(8.52, s0.bitsetterRef, 0);

        for (int line = 1; line <= 30; line++) {
            j.onAck(line);
        }
        JobState s = j.snapshot();
        assertEquals(30, s.lineAcked);
        // 16 newest acked motion lines may be queued: lines 5..30 have motion; the 16th newest is line 15
        int expectedDone = 30 - (PlannerModel.GRBL.plannerBlocks() + 1);
        assertEquals(expectedDone, s.lineDone);
        assertEquals("T1", s.tool);
        assertEquals("G1", s.modal.motion);

        // not written yet (rate limit), then the tick writes it
        assertEquals(0, JobState.read(file).lineAcked);
        clock.ms += 1000;
        j.tick();
        assertEquals(30, JobState.read(file).lineAcked);

        j.onStatus(new double[]{-280.0, -350, -61}, new double[]{-300, -350, -60}, false);
        assertArrayEquals(new double[]{-280, -350, -61}, j.snapshot().lastMpos, 0);
        assertEquals(expectedDone, j.snapshot().lineDone);
        // idle with nothing awaiting: everything acknowledged ran
        j.onStatus(null, null, true);
        assertEquals(30, j.snapshot().lineDone);

        j.onPaused();
        assertEquals("paused", JobState.read(file).stream); // state changes are written at once
        j.onResumed();
        assertEquals("running", JobState.read(file).stream);
        j.setTool("T21");
        for (int line = 31; line <= job.lineCount(); line++) {
            j.onAck(line);
        }
        assertEquals("T21", j.snapshot().tool); // the tool change's tool wins over the job model
        j.onCompleted();
        JobState done = JobState.read(file);
        assertEquals("completed", done.stream);
        assertEquals(job.lineCount(), done.lineDone);
        // after completion nothing changes it
        j.onStopped("canceled");
        assertEquals("completed", JobState.read(file).stream);

        List<String> events = recs.of("job").stream().map(d -> d.get("event").getAsString()).toList();
        // one op per M6 (no op headers in this program)
        assertEquals(List.of("started", "op_start", "paused", "completed"), events);
        JsonObject started = recs.of("job").get(0);
        assertEquals(job.sha256(), started.get("file_sha256").getAsString());
        assertTrue(started.get("line").isJsonNull());
    }

    @Test
    public void opStartRecordsAndStopReasons() throws Exception {
        JobModel job = JobModel.parse("two.nc", Fixtures.TWO_TOOLS, null);
        JournalTracker j = new JournalTracker(recs, clock, 1000, PlannerModel.GRBL);
        j.start(job, tmp.newFolder("s").toPath(), "s1", null, null, "T02", null);
        for (int line = 1; line <= job.lineCount() - 3; line++) {
            j.onAck(line);
        }
        j.onAlarm("HARD_LIMIT");
        j.onStopped("canceled"); // ignored: no longer running
        List<JsonObject> rec = recs.of("job");
        assertEquals(List.of("started", "op_start", "op_start", "stopped"),
                rec.stream().map(d -> d.get("event").getAsString()).toList());
        assertEquals("pocket", rec.get(1).get("op").getAsString());
        assertEquals("finish", rec.get(2).get("op").getAsString());
        assertEquals("alarm: HARD_LIMIT", rec.get(3).get("reason").getAsString());
        assertEquals("alarm", j.snapshot().stream);
        assertEquals("finish", j.snapshot().operation);
    }

    @Test
    public void runningJournalIsMarkedCrashedOnTheNextLaunch() throws Exception {
        JobModel job = JobModel.parse("two.nc", Fixtures.TWO_TOOLS, null);
        Path session = tmp.newFolder("s").toPath();
        JournalTracker j = new JournalTracker(recs, clock, 1000, PlannerModel.GRBL);
        j.start(job, session, "s1", new double[]{-300, -350, -60}, null, "T02", 8.5);
        for (int line = 1; line <= 14; line++) {
            j.onAck(line);
        }
        clock.ms += 1500;
        j.tick();
        // ... UGS dies here; next launch:
        Fixtures.Recs next = new Fixtures.Recs();
        JobState s = JournalTracker.recover(j.path(), next, clock);
        assertEquals("crashed", s.stream);
        assertEquals("crashed", JobState.read(j.path()).stream);
        assertEquals(14, s.lineAcked);
        JsonObject d = next.of("job").get(0);
        assertEquals("stopped", d.get("event").getAsString());
        assertEquals("crashed", d.get("reason").getAsString());
        assertEquals("running", d.get("was").getAsString());
        // a second launch leaves it alone
        Fixtures.Recs again = new Fixtures.Recs();
        assertEquals("crashed", JournalTracker.recover(j.path(), again, clock).stream);
        assertTrue(again.all.isEmpty());
        assertNull(JournalTracker.recover(session.resolve("missing.json"), again, clock));
        assertFalse(Files.exists(session.resolve("missing.json")));
    }
}
