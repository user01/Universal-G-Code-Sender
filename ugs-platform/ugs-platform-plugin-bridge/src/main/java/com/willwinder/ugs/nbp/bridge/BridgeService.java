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

import com.google.gson.JsonObject;
import com.willwinder.ugs.nbp.bridge.capture.Capture;
import com.willwinder.ugs.nbp.bridge.capture.CommandOrigins;
import com.willwinder.ugs.nbp.bridge.log.BridgeHome;
import com.willwinder.ugs.nbp.bridge.log.LogRecord;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.services.LookupService;
import org.openide.modules.OnStart;
import org.openide.modules.OnStop;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point of the bridge module. Owns the long-lived pieces (lock, session log, capture; API later).
 */
@OnStart
public final class BridgeService implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(BridgeService.class.getName());
    private static final BridgeLock LOCK = new BridgeLock();
    private static final CommandOrigins ORIGINS = new CommandOrigins();
    private static volatile SessionLog sessionLog;
    private static volatile Capture capture;

    public static BridgeLock lock() {
        return LOCK;
    }

    /** The session log, or null if the bridge failed to start. */
    public static SessionLog sessionLog() {
        return sessionLog;
    }

    /** The capture, or null if the bridge failed to start. */
    public static Capture capture() {
        return capture;
    }

    public static CommandOrigins origins() {
        return ORIGINS;
    }

    static String bridgeVersion() {
        String v = BridgeService.class.getPackage() == null ? null : BridgeService.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    @Override
    public void run() {
        try {
            start(LookupService.lookup(BackendAPI.class), new SessionLog(BridgeHome.resolve()));
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "ugs-bridge failed to start", e);
        }
    }

    static synchronized void start(BackendAPI backend, SessionLog log) {
        if (sessionLog != null) {
            return;
        }
        log.start();
        sessionLog = log;
        Capture c = new Capture(log, ORIGINS, bridgeVersion());
        capture = c;

        JsonObject start = c.sessionData("bridge_start");
        start.addProperty("home", log.home().toString());
        log.append(LogRecord.Kind.SESSION, LogRecord.Src.BRIDGE, start);
        log.append(LogRecord.Kind.AUTHORITY, LogRecord.Src.BRIDGE,
                authorityData(LOCK.getState(), "bridge started (always locked at launch)", LogRecord.Src.BRIDGE));
        LOCK.addListener(LOCK_LOGGER);

        if (backend != null) {
            c.attach(backend);
            c.startHeartbeat();
        }
        LOGGER.info(() -> "ugs-bridge started (MCP " + LOCK.getState() + ", backend " + (backend != null ? "found" : "missing")
                + ", log " + log.logsRoot() + ")");
    }

    static synchronized void stop() {
        SessionLog log = sessionLog;
        Capture c = capture;
        if (log == null) {
            return;
        }
        LOCK.removeListener(LOCK_LOGGER);
        if (c != null) {
            c.detach();
            log.append(LogRecord.Kind.SESSION, LogRecord.Src.BRIDGE, c.sessionData("bridge_stop"));
        }
        log.close();
        sessionLog = null;
        capture = null;
    }

    private static final Consumer<BridgeLock.State> LOCK_LOGGER = state -> {
        SessionLog log = sessionLog;
        if (log != null) {
            log.append(LogRecord.Kind.AUTHORITY, LogRecord.Src.OPERATOR,
                    authorityData(state, state == BridgeLock.State.LOCKED ? "operator locked Claude access" : "operator unlocked Claude access",
                            LogRecord.Src.OPERATOR));
        }
    };

    /**
     * {@code authority} record data. Until authority levels exist (later package) the level is {@code observe}
     * when locked and {@code query} when unlocked (unlocking never arms Motion/Stream, SPEC §4.3).
     */
    static JsonObject authorityData(BridgeLock.State state, String reason, String by) {
        JsonObject d = new JsonObject();
        d.addProperty("locked", state == BridgeLock.State.LOCKED);
        d.addProperty("level", state == BridgeLock.State.LOCKED ? "observe" : "query");
        d.addProperty("reason", reason);
        d.addProperty("by", by);
        return d;
    }

    /** Closes the session log when the application exits. */
    @OnStop
    public static final class Shutdown implements Runnable {
        @Override
        public void run() {
            stop();
        }
    }
}
