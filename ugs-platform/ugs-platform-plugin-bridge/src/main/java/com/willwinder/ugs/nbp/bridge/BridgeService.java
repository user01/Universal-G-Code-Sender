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
import com.willwinder.ugs.nbp.bridge.api.ApiHost;
import com.willwinder.ugs.nbp.bridge.api.Authority;
import com.willwinder.ugs.nbp.bridge.api.BackendMachineLink;
import com.willwinder.ugs.nbp.bridge.capture.Capture;
import com.willwinder.ugs.nbp.bridge.capture.CommandOrigins;
import com.willwinder.ugs.nbp.bridge.log.BridgeHome;
import com.willwinder.ugs.nbp.bridge.log.LogRecord;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;
import com.willwinder.ugs.nbp.bridge.notify.BridgeNotifier;
import com.willwinder.ugs.nbp.bridge.notify.Notifier;
import com.willwinder.ugs.nbp.bridge.ui.SwingOperatorPrompts;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.services.LookupService;
import org.openide.modules.OnStart;
import org.openide.modules.OnStop;

import java.time.Clock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point of the bridge module. Owns the long-lived pieces (lock, authority, session log, capture, local API).
 */
@OnStart
public final class BridgeService implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(BridgeService.class.getName());
    private static final BridgeLock LOCK = new BridgeLock();
    private static final CommandOrigins ORIGINS = new CommandOrigins();
    private static volatile SessionLog sessionLog;
    private static volatile Capture capture;
    private static volatile Authority authority;
    private static volatile ApiHost apiHost;
    private static volatile BackendMachineLink machineLink;
    private static volatile PendantWatch pendantWatch;
    private static volatile BridgeNotifier notifier;

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

    /** Claude's authority (lock + level), or null if the bridge failed to start. */
    public static Authority authority() {
        return authority;
    }

    /** The local API, or null if it isn't running. */
    public static ApiHost api() {
        return apiHost;
    }

    /** The stock-pendant watcher, or null. */
    public static PendantWatch pendantWatch() {
        return pendantWatch;
    }

    /** Telegram/Slack notifier (also the {@code Notifier} for the API), or null if the bridge failed to start. */
    public static BridgeNotifier notifier() {
        return notifier;
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
            BackendAPI backend = LookupService.lookup(BackendAPI.class);
            start(backend, new SessionLog(BridgeHome.resolve()));
            startApi(backend);
            DevConnect.maybeConnect(backend);
            DevConnect.maybeUnlock(LOCK, authority);
            DevSnapshot.maybeStart();
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
        authority = new Authority(LOCK, log, Clock.systemDefaultZone()); // logs every lock and level change
        authority.logStart();
        notifier = BridgeNotifier.start(log);

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
        stopApi();
        if (authority != null) {
            authority.dispose();
            authority = null;
        }
        if (notifier != null) {
            notifier.close();
            notifier = null;
        }
        if (c != null) {
            c.detach();
            log.append(LogRecord.Kind.SESSION, LogRecord.Src.BRIDGE, c.sessionData("bridge_stop"));
        }
        log.close();
        sessionLog = null;
        capture = null;
    }

    /** Starts the local API (needs UGS's backend). A failure is logged; capture keeps running without it. */
    static synchronized void startApi(BackendAPI backend) {
        SessionLog log = sessionLog;
        if (log == null || backend == null || apiHost != null) {
            return;
        }
        try {
            BackendMachineLink link = new BackendMachineLink(backend, ORIGINS, capture);
            ApiHost host = new ApiHost(log.home(), log, authority, link, new SwingOperatorPrompts(), () -> notifier != null ? notifier : com.willwinder.ugs.nbp.bridge.api.BridgeApi.NO_NOTIFIER);
            host.start();
            machineLink = link;
            apiHost = host;
            PendantWatch pw = new PendantWatch(backend, host.port());
            pw.start();
            pendantWatch = pw;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "ugs-bridge API failed to start", e);
        }
    }

    private static void stopApi() {
        if (pendantWatch != null) {
            pendantWatch.stop();
            pendantWatch = null;
        }
        if (apiHost != null) {
            apiHost.close();
            apiHost = null;
        }
        if (machineLink != null) {
            machineLink.dispose();
            machineLink = null;
        }
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
