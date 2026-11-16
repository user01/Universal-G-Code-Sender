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
package com.willwinder.ugs.nbp.bridge;

import com.willwinder.ugs.nbp.bridge.api.Authority;
import com.willwinder.ugs.nbp.bridge.api.Level;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.time.Clock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** {@code -Dugs.bridge.dev.unlock} is honored only together with a loopback {@code -Dugs.bridge.dev.connect}. */
public class DevUnlockTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void unlockNeedsTheSimulatorConnectProperty() throws Exception {
        try (SessionLog log = new SessionLog(tmp.newFolder().toPath())) {
            BridgeLock lock = new BridgeLock();
            Authority a = new Authority(lock, log, Clock.systemUTC());
            assertFalse(DevConnect.maybeUnlock(null, "true", null, a, Runnable::run));
            assertFalse(DevConnect.maybeUnlock("192.168.1.5:2323", "true", null, a, Runnable::run));
            assertFalse(DevConnect.maybeUnlock("127.0.0.1:2323", null, null, a, Runnable::run));
            assertFalse(DevConnect.maybeUnlock("127.0.0.1:2323", "yes", null, a, Runnable::run));
            assertTrue(lock.isLocked());
            assertTrue(DevConnect.maybeUnlock("127.0.0.1:2323", "true", "motion", a, Runnable::run));
            assertFalse(lock.isLocked());
            assertEquals(Level.MOTION, a.level());
            a.dispose();
        }
    }
}
