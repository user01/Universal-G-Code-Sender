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
import com.willwinder.ugs.nbp.bridge.job.BitSetter;
import com.willwinder.ugs.nbp.bridge.job.OriginStore;
import com.willwinder.ugs.nbp.bridge.log.LogRecord;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Records every G54 change in {@code origins.json} and as an {@code origin} log record (SPEC §11.3).
 *
 * <p>A change is <em>observed</em> as a new G54 value, from status reports ({@code WCO:}) or a {@code $#} read
 * ({@code [G54:...]}); the <em>method</em> comes from what caused it within the last {@value #CAUSE_WINDOW_MS} ms:
 * <ul>
 *   <li>the bridge's own routines ({@link #expect}): tool change (g10), apply origin (restored), BitZero;</li>
 *   <li>a {@code G10 L2 P0|P1} line on the wire: {@code g10};</li>
 *   <li>a {@code G10 L20 P0|P1} line: {@code bitzero_z} (Z only) / {@code bitzero_xyz} (X and Y) when a probe
 *       completed within {@value #PROBE_WINDOW_MS} ms before (UGS's probe module), else {@code manual} (UGS's zero
 *       buttons send {@code G10 P0 L20 X0 ...});</li>
 *   <li>nothing: {@code observed} (another sender changed it, or the first reading after connecting differs from
 *       the store).</li>
 * </ul>
 * The first G54 seen after connecting is recorded only if it differs from the newest stored origin. Values are
 * rounded to 0.001 mm. Not thread-safe: the runtime calls it from its event thread.
 */
public final class OriginTracker {
    static final long CAUSE_WINDOW_MS = 10_000;
    static final long PROBE_WINDOW_MS = 120_000;
    static final double SAME = 0.0005;
    private static final Logger LOGGER = Logger.getLogger(OriginTracker.class.getName());
    private static final Pattern G10 = Pattern.compile("(?i)(?<![A-Z])G0*10(?![0-9.])");
    private static final Pattern L = Pattern.compile("(?i)(?<![A-Z])L\\s*(\\d+)");
    private static final Pattern P = Pattern.compile("(?i)(?<![A-Z])P\\s*(\\d+)");

    /** What a new origin record is stamped with. */
    public record Context(String session, String jobSha256, String tool, Double bitsetterRef, String profileName) {
    }

    /**
     * Comparison of the current G54 with the origin pinned to the loaded job (SPEC §11.3 "on connect").
     *
     * @param matches true when XYZ agree within {@code tolerance}
     * @param delta   current − pinned, mm
     */
    public record Check(String jobSha256, String pinnedId, String pinnedName, double[] pinnedWco,
                        double[] currentWco, boolean matches, double[] delta, double tolerance, String detail) {
    }

    private record Cause(String method, String tool, String note, String sourceId, long atMs) {
    }

    private final Path storePath;
    private final Records records;
    private final Clock clock;
    private final Supplier<Context> context;
    private OriginStore store;
    private double[] lastWco;
    private Cause cause;
    private long lastProbeMs = Long.MIN_VALUE / 2;
    private long lastIdMs;

    public OriginTracker(Path storePath, Records records, Clock clock, Supplier<Context> context) {
        this.storePath = storePath;
        this.records = records;
        this.clock = clock;
        this.context = context;
        try {
            this.store = OriginStore.read(storePath);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not read " + storePath + "; starting an empty origin store", e);
            this.store = new OriginStore();
        }
    }

    public Path storePath() {
        return storePath;
    }

    /** New connection: the next G54 reading is compared with the store instead of the previous reading. */
    public void onConnect() {
        lastWco = null;
        cause = null;
    }

    /** Last G54 seen (mm), or null. */
    public double[] currentWco() {
        return lastWco == null ? null : lastWco.clone();
    }

    /** A probe completed (UGS ProbeEvent or a {@code [PRB:...:1]}). */
    public void onProbe() {
        lastProbeMs = clock.millis();
    }

    /** A line went to the controller: G10 lines set the method of the next change. */
    public void onCommandSent(String line) {
        String method = classify(line, clock.millis() - lastProbeMs <= PROBE_WINDOW_MS);
        if (method == null) {
            return;
        }
        Cause c = cause;
        if (c != null && clock.millis() - c.atMs <= CAUSE_WINDOW_MS && c.note != null) {
            return; // the bridge announced this G10 itself (expect); keep its richer attribution
        }
        cause = new Cause(method, null, null, null, clock.millis());
    }

    /** The bridge is about to change G54 itself. */
    public void expect(String method, String tool, String note, String sourceId) {
        cause = new Cause(method, tool, note == null ? method : note, sourceId, clock.millis());
    }

    /**
     * Method for a line that changes G54, or null if it doesn't. {@code G10 L2|L20} with {@code P0} (active WCS,
     * assumed G54) or {@code P1}.
     */
    static String classify(String line, boolean probedRecently) {
        if (line == null) {
            return null;
        }
        String code = line.replaceAll("\\([^)]*\\)", " ").replaceAll(";.*$", "");
        if (!G10.matcher(code).find()) {
            return null;
        }
        Matcher l = L.matcher(code);
        Matcher p = P.matcher(code);
        if (!l.find()) {
            return null;
        }
        int lv = Integer.parseInt(l.group(1));
        int pv = p.find() ? Integer.parseInt(p.group(1)) : 0;
        if (pv != 0 && pv != 1) {
            return null;
        }
        if (lv == 2) {
            return "g10";
        }
        if (lv != 20) {
            return null;
        }
        if (!probedRecently) {
            return "manual";
        }
        String u = code.toUpperCase(Locale.ROOT);
        boolean xy = u.matches(".*(?<![A-Z])X.*") && u.matches(".*(?<![A-Z])Y.*");
        boolean z = u.matches(".*(?<![A-Z])Z.*");
        return xy ? "bitzero_xyz" : z ? "bitzero_z" : "manual";
    }

    /**
     * A G54 reading (mm). Records an origin when it differs from the previous reading (or, for the first reading
     * after connecting, from the newest stored origin).
     *
     * @param source {@code status} or {@code $#}
     * @return the new origin, or null when nothing changed
     */
    public OriginStore.Origin observe(double[] wcoMm, String source) {
        if (wcoMm == null || wcoMm.length < 3) {
            return null;
        }
        double[] wco = BitSetter.round3(new double[]{wcoMm[0], wcoMm[1], wcoMm[2]});
        double[] prev = lastWco;
        lastWco = wco;
        long now = clock.millis();
        Cause c = cause != null && now - cause.atMs <= CAUSE_WINDOW_MS ? cause : null;
        if (prev != null && same(prev, wco)) {
            return null;
        }
        if (prev == null && c == null) {
            Optional<OriginStore.Origin> latest = store.latest();
            if (latest.isPresent() && latest.get().wco != null && same(latest.get().wco, wco)) {
                return null;
            }
        }
        cause = null;
        Context ctx = context.get();
        OriginStore.Origin o = new OriginStore.Origin(newId(now), "G54", wco,
                c != null ? c.method : "observed", Instant.ofEpochMilli(now));
        o.tool = c != null && c.tool != null ? c.tool : ctx.tool();
        o.bitsetterRef = ctx.bitsetterRef() == null ? null : BitSetter.round3(ctx.bitsetterRef());
        o.session = ctx.session();
        o.jobSha256 = ctx.jobSha256();
        o.machineProfile = ctx.profileName();
        String note = c != null ? c.note : prev == null ? "first reading after connect (" + source + ") differs from the store"
                : "changed outside the bridge (" + source + ")";
        if (c != null && c.sourceId != null) {
            note = note + " (from " + c.sourceId + ")";
        }
        o.note = note;
        store.add(o);
        save();

        JsonObject d = new JsonObject();
        d.addProperty("wcs", o.wcs);
        d.add("wco", Records.array(o.wco));
        d.addProperty("method", o.method);
        d.addProperty("tool", o.tool);
        d.addProperty("bitsetter_ref", o.bitsetterRef);
        d.addProperty("origin_id", o.id);
        d.add("previous_wco", Records.array(prev));
        d.addProperty("source", source);
        d.addProperty("note", o.note);
        records.append(LogRecord.Kind.ORIGIN, "observed".equals(o.method) ? LogRecord.Src.CONTROLLER : LogRecord.Src.BRIDGE, d);
        return o;
    }

    private String newId(long now) {
        long ms = Math.max(now, lastIdMs + 1);
        while (store.find(OriginStore.newId(Instant.ofEpochMilli(ms))).isPresent()) {
            ms++;
        }
        lastIdMs = ms;
        return OriginStore.newId(Instant.ofEpochMilli(ms));
    }

    public List<OriginStore.Origin> list() {
        return new ArrayList<>(store.origins);
    }

    public Optional<OriginStore.Origin> find(String id) {
        return store.find(id);
    }

    public Optional<OriginStore.Origin> latest() {
        return store.latest();
    }

    public Optional<OriginStore.Origin> pinnedFor(String jobSha256) {
        return jobSha256 == null ? Optional.empty() : store.pinnedFor(jobSha256);
    }

    /** Names an origin ("dr ryan stock"); null clears the name. */
    public OriginStore.Origin name(String id, String name) {
        OriginStore.Origin o = store.find(id).orElseThrow(() -> new IllegalArgumentException("no origin " + id));
        o.name = name == null || name.isBlank() ? null : name.trim();
        save();
        return o;
    }

    /** Pins an origin to a job file (by SHA-256). */
    public OriginStore.Origin pin(String id, String jobSha256) {
        store.pin(id, jobSha256);
        save();
        return store.find(id).orElseThrow();
    }

    public OriginStore.Origin unpin(String id, String jobSha256) {
        OriginStore.Origin o = store.find(id).orElseThrow(() -> new IllegalArgumentException("no origin " + id));
        if (o.pinnedTo != null) {
            o.pinnedTo.remove(jobSha256);
        }
        save();
        return o;
    }

    /** Current G54 vs the origin pinned to the job; null when there is no job or nothing pinned. */
    public Check check(String jobSha256, double tolerance) {
        Optional<OriginStore.Origin> pinned = pinnedFor(jobSha256);
        if (pinned.isEmpty()) {
            return null;
        }
        OriginStore.Origin o = pinned.get();
        double[] cur = currentWco();
        if (cur == null) {
            return new Check(jobSha256, o.id, o.name, o.wco, null, false, null, tolerance,
                    "current G54 unknown (not connected, or no WCO reported yet)");
        }
        double[] delta = BitSetter.round3(new double[]{cur[0] - o.wco[0], cur[1] - o.wco[1], cur[2] - o.wco[2]});
        boolean ok = o.matches(cur, tolerance, true);
        String detail = ok ? "G54 matches the pinned origin " + label(o)
                : String.format(Locale.ROOT, "G54 differs from the pinned origin %s by X%+.3f Y%+.3f Z%+.3f mm: "
                + "restore it with apply origin", label(o), delta[0], delta[1], delta[2]);
        return new Check(jobSha256, o.id, o.name, o.wco, cur, ok, delta, tolerance, detail);
    }

    private static String label(OriginStore.Origin o) {
        return o.name != null ? "'" + o.name + "' (" + o.id + ")" : o.id;
    }

    private void save() {
        try {
            store.write(storePath);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not write " + storePath, e);
        }
    }

    static boolean same(double[] a, double[] b) {
        for (int i = 0; i < 3; i++) {
            if (Math.abs(a[i] - b[i]) > SAME) {
                return false;
            }
        }
        return true;
    }
}
