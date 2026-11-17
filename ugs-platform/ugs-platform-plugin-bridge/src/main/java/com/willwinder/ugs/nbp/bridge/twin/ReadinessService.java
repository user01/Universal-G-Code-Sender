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
package com.willwinder.ugs.nbp.bridge.twin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.willwinder.ugs.nbp.bridge.job.JobModel;
import com.willwinder.ugs.nbp.bridge.job.JobSidecar;
import com.willwinder.ugs.nbp.bridge.job.OriginStore;
import com.willwinder.ugs.nbp.bridge.log.LogRecord;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;
import com.willwinder.ugs.nbp.bridge.runtime.RuntimeState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Gathers the {@link Readiness.Facts} from what the bridge already knows (the API's state tracker, the profile,
 * {@code runtime.json}, {@code origins.json}, {@code twin.json}, the loaded file), answers {@code GET
 * /twin/readiness}, records the operator's stock confirmation, and logs an {@code event} record of type
 * {@code readiness} whenever the findings change (checked by {@link #tick()}, which the API host runs every
 * 2 s while relevant records arrived).
 */
public final class ReadinessService implements Consumer<LogRecord> {
    private static final Logger LOGGER = Logger.getLogger(ReadinessService.class.getName());

    /** The live machine facts, from the API's state tracker. */
    public interface Live {
        boolean connected();

        boolean homed();

        /** Live G54 offset (mm) or null. */
        double[] wco();

        /** {@code $$} values seen this session ("$110" → 10000), empty when not read. */
        Map<String, Double> settings();

        /** The loaded file, or null. */
        Path file();

        /** Its SHA-256, or null. */
        String fileSha();
    }

    private final Path home;
    private final SessionLog log;
    private final Supplier<String> profileJson;
    private final Live live;
    private final Clock clock;
    private final List<Consumer<Readiness.Report>> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean dirty = true;
    private String lastSignature;
    private Readiness.Report last;
    // loaded-file cache
    private String cachedKey;
    private JobModel cachedJob;
    private StockSpec cachedStock;

    public ReadinessService(Path home, SessionLog log, Supplier<String> profileJson, Live live, Clock clock) {
        this.home = home;
        this.log = log;
        this.profileJson = profileJson;
        this.live = live;
        this.clock = clock;
    }

    public void addListener(Consumer<Readiness.Report> l) {
        listeners.add(l);
    }

    public void removeListener(Consumer<Readiness.Report> l) {
        listeners.remove(l);
    }

    /** Session-log listener: anything that can change a finding marks the report dirty. */
    @Override
    public void accept(LogRecord r) {
        if (LogRecord.Kind.EVENT.equals(r.kind()) && r.json().contains("\"type\":\"readiness\"")) {
            return; // our own record
        }
        switch (r.kind()) {
            case LogRecord.Kind.STATUS, LogRecord.Kind.ORIGIN, LogRecord.Kind.FILE, LogRecord.Kind.SESSION,
                 LogRecord.Kind.RX, LogRecord.Kind.EVENT, LogRecord.Kind.TOOL -> dirty = true;
            default -> {
            }
        }
    }

    /** The current findings (computed now). */
    public synchronized Readiness.Report report() {
        Readiness.Report r = Readiness.evaluate(facts());
        publish(r);
        return r;
    }

    /** Recomputes when something relevant happened since the last check; logs a record when the findings changed. */
    public void tick() {
        if (!dirty) {
            return;
        }
        dirty = false;
        try {
            report();
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "readiness check failed", e);
        }
    }

    private void publish(Readiness.Report r) {
        String sig = signature(r);
        if (Objects.equals(sig, lastSignature)) {
            return;
        }
        boolean first = lastSignature == null;
        lastSignature = sig;
        last = r;
        JsonObject d = r.toJson();
        d.addProperty("type", "readiness");
        d.addProperty("first", first);
        log.append(LogRecord.Kind.EVENT, LogRecord.Src.BRIDGE, d);
        for (Consumer<Readiness.Report> l : listeners) {
            try {
                l.accept(r);
            } catch (RuntimeException e) {
                LOGGER.log(Level.FINE, "readiness listener", e);
            }
        }
    }

    /** The last published report, or null before the first check. */
    public synchronized Readiness.Report last() {
        return last;
    }

    private static String signature(Readiness.Report r) {
        StringBuilder b = new StringBuilder();
        for (Readiness.Item i : r.items()) {
            b.append(i.id()).append('|').append(i.severity()).append('|').append(i.message()).append('\n');
        }
        return b.toString();
    }

    /**
     * The operator confirms that the loaded file's stock sits where the twin shows it, at the current G54.
     *
     * @param by {@code gui} or {@code viewer}
     * @throws IllegalStateException when no file is loaded or G54 is unknown
     */
    public synchronized TwinStore.Confirmation confirm(String by) {
        String sha = live.fileSha();
        Path file = live.file();
        double[] wco = live.wco();
        if (sha == null) {
            throw new IllegalStateException("no file loaded");
        }
        if (wco == null) {
            throw new IllegalStateException("G54 unknown: connect first");
        }
        TwinStore s = TwinStore.read(storePath());
        TwinStore.Confirmation c = new TwinStore.Confirmation();
        c.sha256 = sha;
        c.file = file == null ? null : file.getFileName().toString();
        c.wco = new double[]{round3(wco[0]), round3(wco[1]), round3(wco[2])};
        c.at = Instant.ofEpochMilli(clock.millis()).toString();
        c.by = by;
        s.confirmation = c;
        try {
            s.write(storePath());
        } catch (IOException e) {
            throw new IllegalStateException("could not write " + storePath() + ": " + e.getMessage(), e);
        }
        JsonObject d = new JsonObject();
        d.addProperty("type", "twin");
        d.addProperty("action", "stock_confirmed");
        d.addProperty("sha256", c.sha256);
        d.addProperty("file", c.file);
        JsonArray w = new JsonArray();
        for (double v : c.wco) {
            w.add(v);
        }
        d.add("wco", w);
        d.addProperty("by", by);
        log.append(LogRecord.Kind.EVENT, LogRecord.Src.OPERATOR, d);
        dirty = true;
        report();
        return c;
    }

    /** The operator's answer to the pre-run dialog: started anyway or cancelled, with the warnings shown. */
    public void logStartDecision(boolean started, Readiness.Report shown) {
        JsonObject d = new JsonObject();
        d.addProperty("type", "twin");
        d.addProperty("action", started ? "start_anyway" : "start_cancelled");
        JsonArray ids = new JsonArray();
        shown.warningIds().forEach(ids::add);
        d.add("warnings", ids);
        log.append(LogRecord.Kind.EVENT, LogRecord.Src.OPERATOR, d);
    }

    private Path storePath() {
        return home.resolve(TwinStore.FILE);
    }

    Readiness.Facts facts() {
        TwinProfile profile = TwinProfile.parse(profileJson.get());
        RuntimeState rt = RuntimeState.read(home.resolve(RuntimeState.FILE));
        List<OriginStore.Origin> origins = List.of();
        Path op = home.resolve("origins.json");
        if (Files.isRegularFile(op)) {
            try {
                origins = OriginStore.read(op).origins;
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, "origins.json unreadable", e);
            }
        }
        Path file = live.file();
        String sha = live.fileSha();
        loadJob(file, sha);
        boolean connected = live.connected();
        return new Readiness.Facts(profile, live.settings(), connected, connected && live.homed(),
                connected ? live.wco() : null, sha, sha == null ? null : cachedJob, sha == null ? null : cachedStock,
                rt.reference, rt.bitsetterToolChanges, origins, TwinStore.read(storePath()).confirmation);
    }

    private void loadJob(Path file, String sha) {
        String key = file + "|" + sha;
        if (key.equals(cachedKey)) {
            return;
        }
        cachedKey = key;
        cachedJob = null;
        cachedStock = null;
        if (file == null || sha == null || !Files.isRegularFile(file)) {
            return;
        }
        JobSidecar sidecar = null;
        try {
            Path sp = JobSidecar.locate(file);
            sidecar = sp == null ? null : JobSidecar.read(sp);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "sidecar unreadable", e);
        }
        try {
            cachedJob = JobModel.load(file, sidecar);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "loaded file unreadable", e);
        }
        cachedStock = StockSpec.of(cachedJob, sidecar);
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
