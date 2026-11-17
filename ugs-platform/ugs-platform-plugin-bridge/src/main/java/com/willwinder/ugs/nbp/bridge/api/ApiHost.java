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
import com.willwinder.ugs.nbp.bridge.eta.EstimateCache;
import com.willwinder.ugs.nbp.bridge.eta.EtaEvents;
import com.willwinder.ugs.nbp.bridge.eta.EtaTracker;
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
    private final EstimateCache estimates;
    private final boolean ownEstimates;
    private final EtaTracker eta;
    private final EtaEvents etaEvents;
    private final java.util.function.Consumer<com.willwinder.ugs.nbp.bridge.log.LogRecord> etaListener;
    private final com.willwinder.ugs.nbp.bridge.twin.ReadinessService readiness;
    private volatile int port = -1;

    /**
     * @param authority owned by the caller (it exists, and logs lock changes, even if the API can't start)
     * @param prompts   asks the operator (Swing dialogs in UGS)
     */
    public ApiHost(Path home, SessionLog log, Authority authority, MachineLink link, OperatorPrompts prompts,
                   Supplier<Notifier> notifier) {
        this(home, log, authority, link, prompts, notifier, (EstimateCache) null);
    }

    /** @param estimates time estimates shared with the notifier, or null for the API's own */
    public ApiHost(Path home, SessionLog log, Authority authority, MachineLink link, OperatorPrompts prompts,
                   Supplier<Notifier> notifier, EstimateCache estimates) {
        this(home, log, authority, link, prompts, notifier, System::currentTimeMillis, estimates);
    }

    /** @param millis wall clock for the status-report age (tests pass a fake one) */
    ApiHost(Path home, SessionLog log, Authority authority, MachineLink link, OperatorPrompts prompts,
            Supplier<Notifier> notifier, java.util.function.LongSupplier millis) {
        this(home, log, authority, link, prompts, notifier, millis, null);
    }

    ApiHost(Path home, SessionLog log, Authority authority, MachineLink link, OperatorPrompts prompts,
            Supplier<Notifier> notifier, java.util.function.LongSupplier millis, EstimateCache estimates) {
        this.home = home;
        this.log = log;
        this.link = link;
        this.authority = authority;
        this.state = new StateTracker(millis);
        this.mcpToken = Discovery.newToken();
        String viewerToken = Discovery.newToken();
        ProfileSource profile = new ProfileSource(home);
        this.ownEstimates = estimates == null;
        this.estimates = estimates != null ? estimates : new EstimateCache(profile::model);
        this.eta = new EtaTracker(this.estimates, () -> millis.getAsLong() * 1_000_000L, java.time.Clock.systemDefaultZone());
        this.etaEvents = new EtaEvents(eta, log);
        this.etaListener = eta::accept;
        this.commands = new CommandService(log, link, authority, prompts, this::queryStatus);
        this.readiness = new com.willwinder.ugs.nbp.bridge.twin.ReadinessService(home, log, profile::json,
                new LiveFacts(), java.time.Clock.systemUTC());
        this.api = new BridgeApi(new BridgeApi.Deps(log, authority, state, commands, link, prompts, profile,
                home, notifier, () -> latestJournal(log.logsRoot()), eta, this.estimates, readiness), mcpToken, viewerToken);
        this.server = new LiteHttpServer(api, api);
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "ugs-bridge-authority-timer"));
        this.reactions = Executors.newSingleThreadExecutor(r -> daemon(r, "ugs-bridge-machine-reactions"));
        log.addListener(state);
        log.addListener(etaListener);
        log.addListener(readiness);
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
        timer.scheduleAtFixedRate(etaEvents::tick, 2, 2, TimeUnit.SECONDS);
        timer.scheduleAtFixedRate(readiness::tick, 1, 2, TimeUnit.SECONDS);
        LOGGER.info(() -> "ugs-bridge API on http://127.0.0.1:" + port + BridgeApi.BASE + " (viewer "
                + (StaticFiles.available() ? "packaged" : "missing") + ")");
    }

    @Override
    public void close() {
        timer.shutdownNow();
        reactions.shutdownNow();
        log.removeListener(state);
        log.removeListener(etaListener);
        log.removeListener(readiness);
        if (ownEstimates) {
            estimates.close();
        }
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

    /** The machine side of the Telegram commands (D26). */
    public TelegramAccess telegramAccess() {
        return new TelegramAccess(state, eta, authority, link, log, readiness::report);
    }

    /** Twin readiness (D27): what the twin shows that is assumed, missing or has drifted. */
    public com.willwinder.ugs.nbp.bridge.twin.ReadinessService readiness() {
        return readiness;
    }

    /** The readiness checks' view of the machine, from the state tracker and the loaded file. */
    private final class LiveFacts implements com.willwinder.ugs.nbp.bridge.twin.ReadinessService.Live {
        private String fpKey;
        private String fpSha;

        @Override
        public boolean connected() {
            return state.connected();
        }

        @Override
        public boolean homed() {
            return state.homed();
        }

        @Override
        public double[] wco() {
            return state.wco();
        }

        @Override
        public java.util.Map<String, Double> settings() {
            java.util.Map<String, Double> out = new java.util.LinkedHashMap<>();
            com.google.gson.JsonObject all = state.settingsJson().getAsJsonObject("settings");
            for (var e : all.entrySet()) {
                com.google.gson.JsonElement v = e.getValue().getAsJsonObject().get("value");
                if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                    out.put(e.getKey(), v.getAsDouble());
                }
            }
            return out;
        }

        @Override
        public Path file() {
            com.google.gson.JsonObject f = state.file();
            if (f != null && f.has("path") && f.get("path").isJsonPrimitive()) {
                return Path.of(f.get("path").getAsString());
            }
            java.io.File lf = link.loadedFile();
            return lf == null ? null : lf.toPath();
        }

        @Override
        public synchronized String fileSha() {
            com.google.gson.JsonObject f = state.file();
            if (f != null && f.has("sha256") && f.get("sha256").isJsonPrimitive()) {
                return f.get("sha256").getAsString();
            }
            Path p = file();
            if (p == null || !Files.isRegularFile(p)) {
                return null;
            }
            try {
                String key = p + "|" + Files.getLastModifiedTime(p).toMillis() + "|" + Files.size(p);
                if (!key.equals(fpKey)) {
                    fpSha = com.willwinder.ugs.nbp.bridge.log.FileFingerprint.of(p).sha256();
                    fpKey = key;
                }
                return fpSha;
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** Live progress and finish time of the streaming job. */
    public EtaTracker eta() {
        return eta;
    }

    /** Emits {@code eta} event records (the timer calls it every 2 s; tests call it directly). */
    public EtaEvents etaEvents() {
        return etaEvents;
    }

    public CommandService commands() {
        return commands;
    }

    /** The viewer URL with this launch's viewer token in the fragment (never sent to the server in requests). */
    public String viewerUrl() {
        return "http://127.0.0.1:" + port + BridgeApi.VIEW + "#vt=" + api.viewerToken();
    }
}
