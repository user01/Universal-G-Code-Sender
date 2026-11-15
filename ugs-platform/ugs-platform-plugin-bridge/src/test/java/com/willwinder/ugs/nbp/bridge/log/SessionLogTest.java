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
package com.willwinder.ugs.nbp.bridge.log;

import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SessionLogTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // 2026-11-15 17:30:01.250 at UTC-5
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-15T22:30:01.250Z"), ZoneOffset.ofHours(-5));
    private final AtomicLong nanos = new AtomicLong(5_000_000_000L);
    private Path home;
    private SessionLog log;

    @Before
    public void setUp() throws Exception {
        home = tmp.newFolder("bridge-home").toPath();
        log = LogFiles.newLog(home, clock, nanos::get, 1000);
    }

    @After
    public void tearDown() {
        log.close();
    }

    private static JsonObject data(String k, String v) {
        JsonObject o = new JsonObject();
        o.addProperty(k, v);
        return o;
    }

    @Test
    public void envelopeBeforeConnectionGoesToNoconnFolderWithNullSession() throws Exception {
        log.start();
        nanos.addAndGet(1_234_567_000L);
        LogRecord r = log.append("message", "ugs", data("text", "hi"));
        assertEquals(1, r.seq());
        assertNull(r.session());
        assertTrue(log.awaitWritten(2000));

        Path file = home.resolve("logs/2026-11-15/173001-noconn/session.jsonl");
        assertTrue(Files.exists(file));
        List<JsonObject> recs = LogFiles.read(file);
        assertEquals(1, recs.size());
        JsonObject o = recs.get(0);
        assertEquals(List.of("seq", "t", "mono", "session", "kind", "src", "data"), new ArrayList<>(o.keySet()));
        assertEquals("2026-11-15T17:30:01.250-05:00", o.get("t").getAsString());
        assertEquals(1.235, o.get("mono").getAsDouble(), 1e-9);
        assertTrue(o.get("session").isJsonNull());
        assertEquals(r.json(), Files.readAllLines(file).get(0));
    }

    @Test
    public void seqIsStrictlyIncreasingAcrossSessions() throws Exception {
        log.start();
        log.append("session", "bridge", data("event", "bridge_start"));
        String s1 = log.openSession(SessionLog.portSlug("tcp_127.0.0.1_2323"));
        assertEquals("20261115-173001-tcp_127.0.0.1_2323", s1);
        log.append("rx", "controller", data("line", "ok"));
        String s2 = log.openSession(SessionLog.portSlug("/dev/cu.usbmodem1101"));
        assertEquals("20261115-173001-_dev_cu.usbmodem1101", s2);
        log.append("rx", "controller", data("line", "ok"));
        // same second, same port: suffix keeps folders apart
        String s3 = log.openSession(SessionLog.portSlug("/dev/cu.usbmodem1101"));
        assertEquals("20261115-173001-_dev_cu.usbmodem1101-2", s3);
        log.append("rx", "controller", data("line", "ok"));
        assertTrue(log.awaitWritten(2000));

        assertTrue(Files.exists(home.resolve("logs/2026-11-15/173001-noconn/session.jsonl")));
        assertTrue(Files.exists(home.resolve("logs/2026-11-15/173001-tcp_127.0.0.1_2323/session.jsonl")));
        assertTrue(Files.exists(home.resolve("logs/2026-11-15/173001-_dev_cu.usbmodem1101/session.jsonl")));
        assertTrue(Files.exists(home.resolve("logs/2026-11-15/173001-_dev_cu.usbmodem1101-2/session.jsonl")));

        List<JsonObject> all = LogFiles.readAll(home);
        assertEquals(4, all.size());
        for (int i = 0; i < all.size(); i++) {
            assertEquals(i + 1, all.get(i).get("seq").getAsLong());
        }
        assertEquals(s1, all.get(1).get("session").getAsString());
        assertEquals(s3, all.get(3).get("session").getAsString());
    }

    @Test
    public void overflowDropsWithoutConsumingSeqAndReportsCount() throws Exception {
        log.close();
        log = LogFiles.newLog(home, clock, nanos::get, 10);
        // writer not started yet: the queue fills up
        int accepted = 0;
        for (int i = 0; i < 25; i++) {
            if (log.append("rx", "controller", data("line", "ok " + i)) != null) {
                accepted++;
            }
        }
        assertEquals(10, accepted);
        assertEquals(15, log.pendingDropped());
        log.start();
        assertTrue(log.awaitWritten(3000));

        List<JsonObject> all = LogFiles.readAll(home);
        assertEquals(11, all.size());
        JsonObject overflow = all.get(10);
        assertEquals("session", overflow.get("kind").getAsString());
        assertEquals("bridge", overflow.get("src").getAsString());
        assertEquals("overflow", overflow.getAsJsonObject("data").get("event").getAsString());
        assertEquals(15, overflow.getAsJsonObject("data").get("dropped").getAsInt());
        assertEquals(11, overflow.get("seq").getAsLong());
        assertEquals(0, log.pendingDropped());
    }

    @Test
    public void defaultQueueHoldsAtLeastOneHundredThousandRecords() {
        assertTrue(SessionLog.DEFAULT_QUEUE_CAPACITY >= 100_000);
    }

    @Test
    public void sinceReturnsRecordsAfterSeqFilteredByKind() {
        for (int i = 0; i < 5; i++) {
            log.append(i % 2 == 0 ? "rx" : "tx", "ugs", data("line", "l" + i));
        }
        List<LogRecord> all = log.since(2, null, 100);
        assertEquals(List.of(3L, 4L, 5L), all.stream().map(LogRecord::seq).toList());
        List<LogRecord> rx = log.since(0, Set.of("rx"), 100);
        assertEquals(List.of(1L, 3L, 5L), rx.stream().map(LogRecord::seq).toList());
        assertEquals(2, log.since(0, null, 2).size());
        assertEquals(5, log.lastSeq());
        assertEquals(1, log.oldestSeqInRing());
    }

    @Test
    public void ringIsBounded() {
        SessionLog small = new SessionLog(home, clock, nanos::get, 100, 3);
        for (int i = 0; i < 10; i++) {
            small.append("rx", "ugs", data("line", "x"));
        }
        assertEquals(List.of(8L, 9L, 10L), small.since(0, null, 100).stream().map(LogRecord::seq).toList());
        small.close();
    }

    @Test
    public void listenersSeeEveryRecord() {
        List<Long> seen = new ArrayList<>();
        log.addListener(r -> seen.add(r.seq()));
        log.append("rx", "ugs", data("line", "a"));
        log.append("rx", "ugs", data("line", "b"));
        assertEquals(List.of(1L, 2L), seen);
    }

    @Test
    public void archiveFileCopiesOncePerSession() throws Exception {
        Path src = tmp.newFile("job.nc").toPath();
        Files.writeString(src, "G21\nG0 X1\nG1 X2 F100\n");
        FileFingerprint fp = FileFingerprint.of(src);
        assertEquals(3, fp.lines());
        assertTrue(fp.sha256().matches("[0-9a-f]{64}"));

        log.openSession("tcp_127.0.0.1_2323");
        String rel = log.archiveFile(fp);
        assertEquals("files/" + fp.sha256() + ".nc", rel);
        Path copy = log.sessionDir().resolve(rel);
        assertEquals(Files.readString(src), Files.readString(copy));
        long mtime = Files.getLastModifiedTime(copy).toMillis();
        Files.setLastModifiedTime(copy, java.nio.file.attribute.FileTime.fromMillis(mtime - 10_000));
        log.archiveFile(fp);
        assertEquals(mtime - 10_000, Files.getLastModifiedTime(copy).toMillis()); // not copied again
    }

    @Test
    public void fingerprintMatchesKnownSha256AndCountsUnterminatedLastLine() throws Exception {
        Path f = tmp.newFile("abc.nc").toPath();
        Files.writeString(f, "abc");
        FileFingerprint fp = FileFingerprint.of(f);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", fp.sha256());
        assertEquals(1, fp.lines());
        assertEquals(3, fp.size());
    }

    @Test
    public void portSlugReplacesSlashes() {
        assertEquals("_dev_cu.usbmodem1101", SessionLog.portSlug("/dev/cu.usbmodem1101"));
        assertEquals("tcp_127.0.0.1_2323", SessionLog.portSlug("tcp_127.0.0.1_2323"));
        assertEquals("unknown", SessionLog.portSlug(null));
    }

    @Test
    public void appendAfterCloseIsIgnored() {
        log.start();
        log.close();
        assertNull(log.append("rx", "ugs", data("line", "x")));
    }

    @Test
    public void homeResolutionOrder() {
        Properties p = new Properties();
        p.setProperty("user.home", "/Users/someone");
        assertEquals(Paths.get("/Users/someone/Library/Application Support/ugs-bridge"), BridgeHome.resolve(p, Map.of()));
        assertEquals(Paths.get("/tmp/env-home"), BridgeHome.resolve(p, Map.of(BridgeHome.ENV, "/tmp/env-home")));
        p.setProperty(BridgeHome.PROPERTY, "/tmp/prop-home");
        assertEquals(Paths.get("/tmp/prop-home"), BridgeHome.resolve(p, Map.of(BridgeHome.ENV, "/tmp/env-home")));
    }

    @Test
    public void writerFlushesEachRecordWithoutClose() throws Exception {
        log.start();
        log.append("rx", "ugs", data("line", "a"));
        assertTrue(log.awaitWritten(2000));
        // readable by another reader while the log is still open
        Path file = LogFiles.sessionFiles(home).get(0);
        assertEquals(1, Files.readAllLines(file).size());
        log.append("rx", "ugs", data("line", "b"));
        assertTrue(log.awaitWritten(2000));
        assertEquals(2, Files.readAllLines(file).size());
        assertNotNull(log.sessionDir());
        assertFalse(Files.readString(file).contains("\n\n"));
    }
}
