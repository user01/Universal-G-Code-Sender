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
import com.willwinder.ugs.nbp.bridge.job.OriginStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OriginTrackerTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    static final class MutableClock extends Clock {
        long ms = Instant.parse("2026-11-15T10:00:00Z").toEpochMilli();

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

    private final MutableClock clock = new MutableClock();
    private final Fixtures.Recs recs = new Fixtures.Recs();
    private String tool = "T02";
    private Double ref = 8.52;

    private OriginTracker tracker(Path store) {
        return new OriginTracker(store, recs, clock,
                () -> new OriginTracker.Context("20261115-100000-tcp", "abc", tool, ref, "test"));
    }

    @Test
    public void classifiesG54Changes() {
        assertEquals("g10", OriginTracker.classify("G10 L2 P1 X-300 Y-350 Z-60", false));
        assertEquals("g10", OriginTracker.classify("G10L2P0Z-58.5", false));
        assertEquals("manual", OriginTracker.classify("G10 P0 L20 X0 Y0 Z0", false)); // UGS zero buttons
        assertEquals("bitzero_z", OriginTracker.classify("G10 L20 P1 Z13.000", true));
        assertEquals("bitzero_xyz", OriginTracker.classify("G10 L20 P0 X0 Y0 Z13", true));
        assertNull(OriginTracker.classify("G10 L2 P2 X0", false));      // G55
        assertNull(OriginTracker.classify("G0 X10 (G10 L2 P1)", false)); // comment
        assertNull(OriginTracker.classify("G1 X10", false));
        assertNull(OriginTracker.classify("G100 L2", false));
    }

    @Test
    public void recordsEachChangeWithItsCause() throws Exception {
        Path store = tmp.getRoot().toPath().resolve("origins.json");
        OriginTracker t = tracker(store);
        t.onConnect();
        // first reading with an empty store: recorded as observed
        OriginStore.Origin first = t.observe(new double[]{-300, -350, -60.0000001}, "status");
        assertNotNull(first);
        assertEquals("observed", first.method);
        assertArrayEquals(new double[]{-300, -350, -60}, first.wco, 0);
        // same value again: nothing
        clock.ms += 200;
        assertNull(t.observe(new double[]{-300, -350, -60}, "status"));

        // UGS zero button
        clock.ms += 1000;
        t.onCommandSent("G10 P0 L20 X0 Y0");
        clock.ms += 200;
        OriginStore.Origin zero = t.observe(new double[]{-150, -300, -60}, "status");
        assertEquals("manual", zero.method);

        // probe module: probe, then G10 L20 Z
        clock.ms += 1000;
        t.onProbe();
        t.onCommandSent("G10 L20 P0 Z13.000");
        clock.ms += 200;
        assertEquals("bitzero_z", t.observe(new double[]{-150, -300, -55.6}, "status").method);

        // the bridge's own G10 keeps its note even though the G10 line is also seen on the wire
        clock.ms += 1000;
        tool = "T21";
        t.expect("g10", "T21", "BitSetter tool change to T21", null);
        t.onCommandSent("G10 L2 P1 Z-43.100");
        clock.ms += 300;
        OriginStore.Origin tc = t.observe(new double[]{-150, -300, -43.1}, "$#");
        assertEquals("g10", tc.method);
        assertEquals("T21", tc.tool);
        assertEquals("BitSetter tool change to T21", tc.note);

        // another sender changed it (no cause within the window)
        clock.ms += 60_000;
        t.onCommandSent("G10 L2 P1 X-1"); // stale cause...
        clock.ms += OriginTracker.CAUSE_WINDOW_MS + 1;
        OriginStore.Origin other = t.observe(new double[]{-1, -300, -43.1}, "status");
        assertEquals("observed", other.method);

        OriginStore read = OriginStore.read(store);
        assertEquals(5, read.origins.size());
        assertEquals(List.of("observed", "manual", "bitzero_z", "g10", "observed"),
                read.origins.stream().map(o -> o.method).toList());
        assertEquals(8.52, read.origins.get(0).bitsetterRef, 0);
        assertEquals("20261115-100000-tcp", read.origins.get(0).session);
        assertEquals("abc", read.origins.get(0).jobSha256);
        assertEquals(5, read.origins.stream().map(o -> o.id).distinct().count());

        List<JsonObject> logged = recs.of("origin");
        assertEquals(5, logged.size());
        JsonObject d = logged.get(3);
        assertEquals("G54", d.get("wcs").getAsString());
        assertEquals("g10", d.get("method").getAsString());
        assertEquals(-43.1, d.getAsJsonArray("wco").get(2).getAsDouble(), 0);
        assertEquals(tc.id, d.get("origin_id").getAsString());
        assertEquals("$#", d.get("source").getAsString());
        assertEquals(-55.6, d.getAsJsonArray("previous_wco").get(2).getAsDouble(), 0);
        assertEquals("controller", recs.all.stream().filter(r -> r.kind().equals("origin")).findFirst().get().src());
    }

    @Test
    public void firstReadingAfterConnectIsRecordedOnlyWhenItDiffersFromTheStore() throws Exception {
        Path store = tmp.getRoot().toPath().resolve("origins.json");
        OriginTracker t = tracker(store);
        t.onConnect();
        t.observe(new double[]{-300, -350, -60}, "status");
        // reconnect (new tracker = new launch): same G54 → nothing new
        OriginTracker t2 = tracker(store);
        t2.onConnect();
        clock.ms += 1000;
        assertNull(t2.observe(new double[]{-300, -350, -60}, "status"));
        // reconnect after another sender reset G54
        t2.onConnect();
        clock.ms += 1000;
        OriginStore.Origin o = t2.observe(new double[]{0, 0, 0}, "status");
        assertEquals("observed", o.method);
        assertTrue(o.note, o.note.contains("first reading after connect"));
    }

    @Test
    public void nameAndPinAndCheck() throws Exception {
        Path store = tmp.getRoot().toPath().resolve("origins.json");
        OriginTracker t = tracker(store);
        t.onConnect();
        OriginStore.Origin o = t.observe(new double[]{-300, -350, -60}, "status");
        t.name(o.id, "dr ryan stock");
        t.pin(o.id, "sha-job");
        assertTrue(t.check("sha-job", 0.01).matches());
        assertNull(t.check("other", 0.01));

        clock.ms += 1000;
        t.observe(new double[]{-300.02, -350, -60}, "status"); // e.g. Carbide Motion re-zeroed
        OriginTracker.Check c = t.check("sha-job", 0.01);
        assertFalse(c.matches());
        assertEquals(o.id, c.pinnedId());
        assertEquals("dr ryan stock", c.pinnedName());
        assertEquals(-0.02, c.delta()[0], 1e-9);
        assertTrue(c.detail(), c.detail().contains("X-0.020"));

        OriginStore read = OriginStore.read(store);
        assertEquals("dr ryan stock", read.find(o.id).get().name);
        assertEquals(List.of("sha-job"), read.find(o.id).get().pinnedTo);
    }
}
