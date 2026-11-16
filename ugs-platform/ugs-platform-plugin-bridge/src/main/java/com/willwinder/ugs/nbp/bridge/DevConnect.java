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
import com.willwinder.universalgcodesender.connection.ConnectionDriver;
import com.willwinder.universalgcodesender.model.BackendAPI;

import java.util.logging.Logger;

/**
 * Development-only: connect to the simulator at launch when {@code -Dugs.bridge.dev.connect=127.0.0.1:<port>} is set
 * (bridge/scripts/dev.sh run-sim). Loopback TCP only; anything else is refused. Never touches serial ports.
 */
final class DevConnect {
    static final String PROPERTY = "ugs.bridge.dev.connect";
    static final String UNLOCK_PROPERTY = "ugs.bridge.dev.unlock";
    static final String LEVEL_PROPERTY = "ugs.bridge.dev.level";
    private static final Logger LOGGER = Logger.getLogger(DevConnect.class.getName());

    private DevConnect() {
    }

    /** Parses "127.0.0.1:2323" (or "localhost:2323"); returns the port, or -1 when the value isn't a loopback address. */
    static int loopbackPort(String value) {
        if (value == null) {
            return -1;
        }
        String[] parts = value.trim().split(":");
        if (parts.length != 2 || !(parts[0].equals("127.0.0.1") || parts[0].equals("localhost"))) {
            return -1;
        }
        try {
            int port = Integer.parseInt(parts[1]);
            return port > 0 && port < 65536 ? port : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static void maybeConnect(BackendAPI backend) {
        String value = System.getProperty(PROPERTY);
        if (value == null || backend == null) {
            return;
        }
        int port = loopbackPort(value);
        if (port < 0) {
            LOGGER.warning(() -> "Ignoring " + PROPERTY + "=" + value + ": only 127.0.0.1:<port> is allowed");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(3000); // let the window system and listeners come up first
                backend.getSettings().setConnectionDriver(ConnectionDriver.TCP);
                backend.getSettings().setPort("127.0.0.1");
                backend.getSettings().setPortRate(Integer.toString(port));
                backend.connect(backend.getSettings().getFirmwareVersion(), "127.0.0.1", port);
                LOGGER.info(() -> "dev connect: TCP 127.0.0.1:" + port);
            } catch (Exception e) {
                LOGGER.log(java.util.logging.Level.WARNING, "dev connect to 127.0.0.1:" + port + " failed", e);
            }
        }, "ugs-bridge-dev-connect");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Development only: starts with Claude access unlocked (and optionally at {@code -Dugs.bridge.dev.level}) when
     * {@code -Dugs.bridge.dev.unlock=true} is set <em>together with</em> a valid loopback
     * {@code -Dugs.bridge.dev.connect}, i.e. only in {@code dev.sh run-sim} against the simulator. Never in a
     * normal launch. Logged loudly, in the UGS log and as an {@code authority} record by the bridge.
     *
     * @return true if it unlocked
     */
    static boolean maybeUnlock(BridgeLock lock, Authority authority) {
        return maybeUnlock(System.getProperty(PROPERTY), System.getProperty(UNLOCK_PROPERTY), System.getProperty(LEVEL_PROPERTY),
                authority);
    }

    static boolean maybeUnlock(String connect, String unlock, String level, Authority authority) {
        if (!"true".equals(unlock) || authority == null) {
            return false;
        }
        if (loopbackPort(connect) < 0) {
            LOGGER.warning(() -> "Ignoring " + UNLOCK_PROPERTY + ": only honored together with " + PROPERTY + "=127.0.0.1:<port>");
            return false;
        }
        LOGGER.warning(() -> "DEVELOPMENT: Claude access starts UNLOCKED (" + UNLOCK_PROPERTY + ", simulator " + connect + ")");
        authority.unlockForDevelopment("DEVELOPMENT ONLY: unlocked at launch by -D" + UNLOCK_PROPERTY + " (simulator " + connect + ")");
        if (level != null && !level.isBlank()) {
            try {
                Level l = Level.parse(level);
                authority.setLevel(l, "bridge", "DEVELOPMENT ONLY: level " + l.id() + " set at launch by -D" + LEVEL_PROPERTY);
            } catch (IllegalArgumentException e) {
                LOGGER.warning(() -> "Ignoring " + LEVEL_PROPERTY + "=" + level);
            }
        }
        return true;
    }
}
