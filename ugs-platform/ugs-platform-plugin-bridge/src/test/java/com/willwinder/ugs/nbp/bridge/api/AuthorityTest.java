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
package com.willwinder.ugs.nbp.bridge.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.willwinder.ugs.nbp.bridge.BridgeLock;
import com.willwinder.ugs.nbp.bridge.log.LogRecord;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** SPEC §4.3: lock, levels, Motion expiry and disarm rules, and their authority records. */
public class AuthorityTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    private SessionLog log;
    private BridgeLock lock;
    private ApiFixture.TestClock clock;
    private Authority authority;
    private final List<Authority.Change> changes = Collections.synchronizedList(new ArrayList<>());

    @Before
    public void setUp() throws Exception {
        log = new SessionLog(tmp.newFolder("home").toPath());
        lock = new BridgeLock();
        clock = new ApiFixture.TestClock();
        authority = new Authority(lock, log, clock);
        authority.addListener((c, s) -> changes.add(c));
    }

    @After
    public void tearDown() {
        authority.dispose();
        log.close();
    }

    private List<JsonObject> authorityRecords() {
        return log.since(0, Set.of(LogRecord.Kind.AUTHORITY), 1000).stream()
                .map(r -> JsonParser.parseString(r.json()).getAsJsonObject().getAsJsonObject("data")).toList();
    }

    @Test
    public void startsLockedAtQueryAndLevelsNeedUnlocking() {
        assertTrue(authority.isLocked());
        assertEquals(Level.QUERY, authority.level());
        authority.setLevel(Level.MOTION, "operator");
        assertEquals("ignored while locked", Level.QUERY, authority.level());
        lock.unlock();
        authority.setLevel(Level.MOTION, "operator");
        assertEquals(Level.MOTION, authority.level());
        assertNotNull(authority.snapshot().motionExpiresAt());
    }

    @Test
    public void lockingDisarmsAndUnlockingNeverReturnsToAnArmedLevel() {
        lock.unlock();
        authority.setLevel(Level.STREAM, "operator");
        lock.lock();
        assertEquals(Level.QUERY, authority.level());
        lock.unlock();
        assertEquals(Level.QUERY, authority.level());
        assertTrue(changes.contains(Authority.Change.LOCKED));
    }

    @Test
    public void unlockReturnsToTheOperatorsBaseLevel() {
        lock.unlock();
        authority.setLevel(Level.OBSERVE, "operator");
        authority.setLevel(Level.MOTION, "operator");
        lock.lock();
        lock.unlock();
        assertEquals(Level.OBSERVE, authority.level());
        authority.setLevel(Level.MOTION, "operator");
        authority.disarm("ALARM:1");
        assertEquals(Level.OBSERVE, authority.level());
    }

    @Test
    public void motionExpiresAfterFifteenIdleMinutesAndUseRestartsTheTimer() throws Exception {
        lock.unlock();
        authority.setLevel(Level.MOTION, "operator");
        clock.advance(Duration.ofMinutes(14));
        authority.touch();
        clock.advance(Duration.ofMinutes(14));
        authority.checkExpiry();
        assertEquals(Level.MOTION, authority.level());
        clock.advance(Duration.ofMinutes(1));
        authority.checkExpiry();
        assertEquals(Level.QUERY, authority.level());
        assertNull(authority.snapshot().motionExpiresAt());
        ApiFixture.await(() -> changes.contains(Authority.Change.DISARMED), "disarm event");
        JsonObject last = authorityRecords().get(authorityRecords().size() - 1);
        assertTrue(last.get("reason").getAsString().contains("expired"));
        assertEquals("bridge", last.get("by").getAsString());
    }

    @Test
    public void disarmOnlyAffectsArmedLevels() {
        lock.unlock();
        authority.disarm("ALARM:1");
        assertEquals(Level.QUERY, authority.level());
        assertFalse(changes.contains(Authority.Change.DISARMED));
    }

    @Test
    public void requestsAreRecordedAndGrantNothing() {
        lock.unlock();
        authority.request(Level.MOTION, "probe Z");
        assertEquals(Level.QUERY, authority.level());
        assertEquals(Level.MOTION, authority.snapshot().pendingRequest().level());
        JsonObject r = authorityRecords().get(authorityRecords().size() - 1);
        assertEquals("claude", r.get("by").getAsString());
        assertEquals("motion", r.get("requested").getAsString());
        assertEquals("query", r.get("level").getAsString());
        authority.setLevel(Level.MOTION, "operator");
        assertNull("granting clears the request", authority.snapshot().pendingRequest());
    }

    @Test
    public void everyChangeIsLogged() {
        authority.logStart();
        lock.unlock();
        authority.setLevel(Level.MOTION, "operator");
        lock.lock();
        List<JsonObject> recs = authorityRecords();
        assertEquals(4, recs.size());
        assertEquals(List.of(true, false, false, true), recs.stream().map(r -> r.get("locked").getAsBoolean()).toList());
        assertEquals(List.of("query", "query", "motion", "query"), recs.stream().map(r -> r.get("level").getAsString()).toList());
        assertTrue(recs.get(2).has("motion_expires_at"));
        assertEquals("operator", recs.get(3).get("by").getAsString());
    }

    @Test
    public void developmentUnlockIsLoggedAsTheBridge() {
        authority.unlockForDevelopment("DEVELOPMENT ONLY: test");
        assertFalse(lock.isLocked());
        JsonObject r = authorityRecords().get(authorityRecords().size() - 1);
        assertEquals("bridge", r.get("by").getAsString());
        assertTrue(r.get("reason").getAsString().startsWith("DEVELOPMENT ONLY"));
    }
}
