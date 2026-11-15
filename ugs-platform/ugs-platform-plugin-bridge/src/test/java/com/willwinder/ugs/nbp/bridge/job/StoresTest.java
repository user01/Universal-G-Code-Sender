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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StoresTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final JobModel job = JobModel.parse("two.nc", Programs.TWO_TOOLS, null);

    static class MutableClock extends Clock {
        long ms;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(ms);
        }
    }

    private JobState sample() {
        JobState s = JobState.start(job, "2026-11-15T12-00-00", Instant.parse("2026-11-15T12:00:00Z"));
        s.lineAcked = 14;
        s.lineDone = 12;
        s.lastMpos = new double[]{-280, -340, -62};
        s.wco = Programs.WCO.clone();
        s.tool = "T02";
        s.operation = "pocket";
        s.bitsetterRef = 31.25;
        s.originId = "o-1";
        s.modal = job.modalAt(14).toJournal();
        return s;
    }

    @Test
    public void journalRoundTripAndAtomicWrite() throws IOException {
        Path dir = tmp.newFolder("session").toPath();
        Path p = JobState.pathIn(dir, job.sha256());
        assertEquals("job-" + job.sha256() + ".state.json", p.getFileName().toString());
        JobState s = sample();
        s.write(p);
        s.lineAcked = 15;
        s.write(p);
        JobState back = JobState.read(p);
        assertEquals(15, back.lineAcked);
        assertEquals(12, back.lineDone);
        assertArrayEquals(Programs.WCO, back.wco, 0);
        assertEquals("G21", back.modal.units);
        assertEquals(1778.0, back.modal.feed, 0);
        assertEquals(job.sha256(), back.file.sha256);
        assertEquals(job.lineCount(), back.file.lines);
        String json = Files.readString(p);
        assertTrue(json.contains("\"line_acked\": 15"));
        assertTrue(json.contains("\"bitsetter_ref\": 31.25"));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(p), files.toList()); // no temp files left behind
        }
    }

    @Test
    public void readRejectsOtherFormats() throws IOException {
        Path p = tmp.newFile("x.json").toPath();
        Files.writeString(p, "{\"format\": \"something-else\"}");
        try {
            JobState.read(p);
            fail();
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void journalWriterThrottles() throws IOException {
        Path p = tmp.getRoot().toPath().resolve("j.json");
        MutableClock clock = new MutableClock();
        clock.ms = 1_000_000;
        JournalWriter w = new JournalWriter(p, clock, 1000);
        JobState s = sample();
        assertTrue(w.update(s)); // first write
        s.lineAcked = 20;
        clock.ms += 200;
        assertFalse(w.update(s));
        assertEquals(14, JobState.read(p).lineAcked);
        s.stream = "paused"; // stream change writes immediately
        assertTrue(w.update(s));
        assertEquals("paused", JobState.read(p).stream);
        s.lineAcked = 21;
        clock.ms += 100;
        assertFalse(w.update(s));
        clock.ms += 1000;
        s.lineAcked = 22;
        assertTrue(w.update(s));
        assertEquals(22, JobState.read(p).lineAcked);
        s.lineAcked = 23;
        assertFalse(w.update(s));
        assertTrue(w.flush());
        assertEquals(23, JobState.read(p).lineAcked);
        assertFalse(w.flush());
        assertEquals(Instant.ofEpochMilli(clock.ms).toString(), JobState.read(p).updated);
    }

    @Test
    public void originStore() throws IOException {
        Path p = tmp.getRoot().toPath().resolve("origins.json");
        OriginStore store = OriginStore.read(p);
        assertTrue(store.origins.isEmpty());
        OriginStore.Origin a = new OriginStore.Origin("o-1", "G54", new double[]{-300, -350, -60}, "bitzero_xyz",
                Instant.parse("2026-11-15T12:00:00Z"));
        a.name = "dr ryan stock";
        a.tool = "T02";
        a.bitsetterRef = 31.25;
        OriginStore.Origin b = new OriginStore.Origin("o-2", "G54", new double[]{-10, -20, -30}, "manual",
                Instant.parse("2026-11-15T12:00:00.5Z"));
        store.add(a);
        store.add(b);
        try {
            store.add(a);
            fail();
        } catch (IllegalArgumentException expected) {
            // duplicate
        }
        store.pin("o-1", job.sha256());
        store.pin("o-1", job.sha256());
        store.write(p);
        OriginStore back = OriginStore.read(p);
        assertEquals(2, back.origins.size());
        assertEquals("o-1", back.pinnedFor(job.sha256()).orElseThrow().id);
        assertEquals(List.of(job.sha256()), back.find("o-1").orElseThrow().pinnedTo);
        assertEquals("o-2", back.latest().orElseThrow().id);
        assertTrue(back.pinnedFor("other").isEmpty());
        assertTrue(back.find("o-1").orElseThrow().matches(new double[]{-300.005, -350, -61}, 0.01, false));
        assertFalse(back.find("o-1").orElseThrow().matches(new double[]{-300.005, -350, -61}, 0.01, true));
        assertTrue(OriginStore.newId(Instant.ofEpochMilli(5)).startsWith("o-"));
    }
}
