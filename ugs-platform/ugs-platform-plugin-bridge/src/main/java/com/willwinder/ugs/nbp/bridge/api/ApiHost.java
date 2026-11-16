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

import com.willwinder.ugs.nbp.bridge.api.http.LiteHttpServer;
import com.willwinder.ugs.nbp.bridge.log.LogRecord;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;
import com.willwinder.ugs.nbp.bridge.notify.Notifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Wires the API together and runs it: authority, state tracker, Claude's command queue, the HTTP/WS server on
 * 127.0.0.1 (8765, else the next free port up to 8775) and the discovery files. Created by {@code BridgeService}.
 */
public final class ApiHost implements AutoCloseable {
    public static final int FIRST_PORT = 8765;
    public static final int LAST_PORT = 8775;
    private static final Logger LOGGER = Logger.getLogger(ApiHost.class.getName());

    private final Path home;
    private final SessionLog log;
    private final Authority authority;
    private final StateTracker state;
    private final MachineLink link;
    private final CommandService commands;
    private final BridgeApi api;
    private final LiteHttpServer server;
    private final ScheduledExecutorService timer;
    private final ExecutorService reactions;
    private final String mcpToken;
    private volatile int port = -1;

    /**
     * @param authority owned by the caller (it exists, and logs lock changes, even if the API can't start)
     * @param prompts   asks the operator (Swing dialogs in UGS)
     */
    public ApiHost(Path home, SessionLog log, Authority authority, MachineLink link, OperatorPrompts prompts,
                   Supplier<Notifier> notifier) {
        this(home, log, authority, link, prompts, notifier, System::currentTimeMillis);
    }

    /** @param millis wall clock for the status-report age (tests pass a fake one) */
    ApiHost(Path home, SessionLog log, Authority authority, MachineLink link, OperatorPrompts prompts,
            Supplier<Notifier> notifier, java.util.function.LongSupplier millis) {
        this.home = home;
        this.log = log;
        this.link = link;
        this.authority = authority;
        this.state = new StateTracker(millis);
        this.mcpToken = Discovery.newToken();
        String viewerToken = Discovery.newToken();
        this.commands = new CommandService(log, link, authority, prompts, this::queryStatus);
        this.api = new BridgeApi(new BridgeApi.Deps(log, authority, state, commands, link, prompts, new ProfileSource(home),
                home, notifier, () -> latestJournal(log.logsRoot())), mcpToken, viewerToken);
        this.server = new LiteHttpServer(api, api);
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "ugs-bridge-authority-timer"));
        this.reactions = Executors.newSingleThreadExecutor(r -> daemon(r, "ugs-bridge-machine-reactions"));
        log.addListener(state);
        // Machine events arrive on UGS's threads (inside the capture); react on our own thread.
        state.addTriggerListener((trigger, why) -> reactions.execute(() -> onMachine(trigger, why)));
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private void onMachine(StateTracker.Trigger trigger, String why) {
        switch (trigger) {
            case ALARM -> {
                commands.dropAll(why);
                authority.disarm(why);
            }
            case RESET, DISCONNECT -> {
                commands.dropAll(why);
                link.abandonPending(why);
                authority.disarm(why);
            }
            case CONNECT -> {
            }
        }
    }

    /** Starts the server and writes {@code token} and {@code bridge.json}. */
    public void start() throws IOException {
        start(FIRST_PORT, LAST_PORT);
    }

    public void start(int firstPort, int lastPort) throws IOException {
        port = server.start(firstPort, lastPort);
        api.setPort(port);
        Discovery.write(home, port, mcpToken, BridgeApi.BASE);
        timer.scheduleAtFixedRate(authority::checkExpiry, 5, 5, TimeUnit.SECONDS);
        timer.scheduleAtFixedRate(this::checkSilence, 2, 2, TimeUnit.SECONDS);
        LOGGER.info(() -> "ugs-bridge API on http://127.0.0.1:" + port + BridgeApi.BASE + " (viewer "
                + (StaticFiles.available() ? "packaged" : "missing") + ")");
    }

    @Override
    public void close() {
        timer.shutdownNow();
        reactions.shutdownNow();
        log.removeListener(state);
        commands.stop();
        api.closeAll();
        server.close();
        Discovery.remove(home);
    }

    /** Seconds without a status report (while connected) before a {@code controller_silent} event is logged. */
    static final double SILENT_EVENT_S = 10.0;
    private boolean silentLogged;

    /**
     * Watchdog (every 2 s): a connected controller that stops reporting (USB or EMI freeze; UGS keeps showing it
     * connected) gets one {@code event} record {@code controller_silent}, which the notifier sends as
     * {@code connection_lost}; {@code controller_responding} follows when reports resume.
     */
    void checkSilence() {
        try {
            double age = state.reportAgeSeconds();
            if (age >= SILENT_EVENT_S && !silentLogged) {
                silentLogged = true;
                com.google.gson.JsonObject d = new com.google.gson.JsonObject();
                d.addProperty("type", "controller_silent");
                d.addProperty("age_s", Math.round(age * 10) / 10.0);
                log.append(com.willwinder.ugs.nbp.bridge.log.LogRecord.Kind.EVENT, "bridge", d);
            } else if (silentLogged && age >= 0 && age < StateTracker.SILENT_AFTER_MS / 1000.0) {
                silentLogged = false;
                com.google.gson.JsonObject d = new com.google.gson.JsonObject();
                d.addProperty("type", "controller_responding");
                log.append(com.willwinder.ugs.nbp.bridge.log.LogRecord.Kind.EVENT, "bridge", d);
            } else if (age < 0) {
                silentLogged = false;
            }
        } catch (RuntimeException e) {
            LOGGER.log(java.util.logging.Level.WARNING, "silence check failed", e);
        }
    }

    /** Answers the {@code ?} line: asks for a status report and waits for it (up to 1.5 s). */
    private List<String> queryStatus() {
        try {
            long seq = state.statusSeq();
            link.realtime("status", LogRecord.Src.CLAUDE);
            var st = state.awaitStatusAfter(seq, 1500);
            if (st == null) {
                st = state.controller(); // status records are de-duplicated: an unchanged report isn't logged
            }
            return st != null && st.has("raw") ? List.of(st.get("raw").getAsString()) : List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "status query failed", e);
            return List.of();
        }
    }

    /** The newest job journal ({@code logs/<day>/<session>/job-*.state.json}), if any. */
    static Optional<Path> latestJournal(Path logsRoot) {
        if (!Files.isDirectory(logsRoot)) {
            return Optional.empty();
        }
        try (Stream<Path> s = Files.find(logsRoot, 3, (p, a) -> a.isRegularFile()
                && p.getFileName().toString().startsWith("job-") && p.getFileName().toString().endsWith(".state.json"))) {
            return s.max(Comparator.comparingLong(p -> p.toFile().lastModified()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public int port() {
        return port;
    }

    public Authority authority() {
        return authority;
    }

    public StateTracker state() {
        return state;
    }

    public BridgeApi api() {
        return api;
    }

    public CommandService commands() {
        return commands;
    }

    /** The viewer URL with this launch's viewer token in the fragment (never sent to the server in requests). */
    public String viewerUrl() {
        return "http://127.0.0.1:" + port + BridgeApi.VIEW + "#vt=" + api.viewerToken();
    }
}
