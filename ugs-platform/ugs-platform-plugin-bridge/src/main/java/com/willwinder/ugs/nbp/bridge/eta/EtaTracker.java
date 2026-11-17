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
package com.willwinder.ugs.nbp.bridge.eta;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.willwinder.ugs.nbp.bridge.job.PlannerModel;
import com.willwinder.ugs.nbp.bridge.job.estimate.Estimate;
import com.willwinder.ugs.nbp.bridge.log.LogRecord;

import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live progress and finish-time prediction for the streaming job (PLAN D25), fed with session-log records.
 *
 * <ul>
 *   <li>Progress: the highest file line acknowledged ({@code tx file_line} + {@code rx ok} with the same
 *       {@code cmd_id}), minus the lines still in the controller's planner (up to its block count of timed lines;
 *       none once it reports Idle). The estimate's {@code line_end_s} turns that line into machine seconds done.</li>
 *   <li>Machine time: wall time since the stream started minus pauses: tool changes (interceptor active), feed holds
 *       ({@code Hold}/{@code Door}) and paused streams.</li>
 *   <li>Ratio: actual / estimated machine seconds over the last {@link #WINDOW_S} s (the whole run until it has
 *       {@link #MIN_EST_S} s of estimate), clamped to 0.5–3. Remaining machine time × ratio + the automatic part of
 *       the tool changes still ahead = adjusted remaining. The operator's tool-change time is not included.</li>
 * </ul>
 * Thread-safe; record handling is cheap (the estimate is computed by {@link EstimateCache} in the background).
 */
public final class EtaTracker {
    /** Capture's {@code file_line} is {@code getCommandNumber() + 1}, one more than the file line (PLAN W3). */
    static final int CAPTURE_FILE_LINE_OFFSET = 1;
    static final double WINDOW_S = 300;
    static final double MIN_EST_S = 20;
    static final double RATIO_MIN = 0.5;
    static final double RATIO_MAX = 3.0;
    private static final Pattern SETTING = Pattern.compile("^\\$(\\d+)=(-?[\\d.]+)");

    private final EstimateCache cache;
    private final LongSupplier nanos;
    private final Clock wall;

    // controller facts (survive streams)
    private final Map<String, Double> settings = new HashMap<>();
    private Integer plannerBlocks;
    private double[] wco;
    private String state;

    // the stream
    private boolean active;      // a stream started and hasn't ended
    private String result;       // completed, canceled, error (after the end)
    private Path file;
    private String sha;
    private Estimate estimate;
    private long startNs;
    private long endNs;
    private long pausedNs;
    private long pauseSinceNs = -1;
    private boolean interceptor;
    private int interceptorLine;
    private boolean hold;
    private boolean streamPaused;
    private int ackedLine;
    private final Map<String, Integer> lineByCmd = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> e) {
            return size() > 512;
        }
    };
    private final Deque<double[]> samples = new ArrayDeque<>(); // {machine s, estimated done s}

    public EtaTracker(EstimateCache cache, LongSupplier nanos, Clock wall) {
        this.cache = cache;
        this.nanos = nanos;
        this.wall = wall;
    }

    public void accept(LogRecord r) {
        if (!relevant(r.kind())) {
            return;
        }
        try {
            onRecord(JsonParser.parseString(r.json()).getAsJsonObject());
        } catch (RuntimeException e) {
            // malformed record: ignore
        }
    }

    public static boolean relevant(String kind) {
        return switch (kind) {
            case LogRecord.Kind.TX, LogRecord.Kind.RX, LogRecord.Kind.STATUS, LogRecord.Kind.EVENT,
                 LogRecord.Kind.FILE, LogRecord.Kind.SESSION -> true;
            default -> false;
        };
    }

    /** One parsed record (the envelope with {@code kind} and {@code data}). */
    public synchronized void onRecord(JsonObject rec) {
        String kind = str(rec, "kind");
        JsonObject d = rec.has("data") && rec.get("data").isJsonObject() ? rec.getAsJsonObject("data") : new JsonObject();
        if (kind == null) {
            return;
        }
        long now = nanos.getAsLong();
        switch (kind) {
            case LogRecord.Kind.TX -> {
                if (active && d.has("file_line") && d.get("file_line").isJsonPrimitive() && str(d, "cmd_id") != null) {
                    lineByCmd.put(str(d, "cmd_id"), d.get("file_line").getAsInt() - CAPTURE_FILE_LINE_OFFSET);
                }
            }
            case LogRecord.Kind.RX -> {
                String line = str(d, "line");
                if (line == null) {
                    return;
                }
                Matcher m = SETTING.matcher(line);
                if (m.find()) {
                    try {
                        settings.put("$" + m.group(1), Double.parseDouble(m.group(2)));
                    } catch (NumberFormatException e) {
                        // ignore
                    }
                } else if (line.startsWith("[OPT:")) {
                    int blocks = PlannerModel.fromOpt(line, PlannerModel.GRBL).plannerBlocks();
                    plannerBlocks = blocks;
                } else if (line.equals("ok") && active) {
                    Integer fl = lineByCmd.remove(str(d, "cmd_id"));
                    if (fl != null && fl > ackedLine) {
                        ackedLine = fl;
                    }
                }
            }
            case LogRecord.Kind.STATUS -> {
                state = str(d, "state");
                if (d.has("wco") && d.get("wco").isJsonArray() && d.getAsJsonArray("wco").size() == 3) {
                    var a = d.getAsJsonArray("wco");
                    wco = new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
                }
                hold = state != null && (state.startsWith("Hold") || state.startsWith("Door"));
                updatePause(now);
            }
            case LogRecord.Kind.EVENT -> onEvent(d, now);
            case LogRecord.Kind.FILE -> {
                if ("stream_start".equals(str(d, "action"))) {
                    String p = str(d, "path");
                    file = p == null ? null : Path.of(p);
                    sha = str(d, "sha256");
                    if (!active) {
                        start(now);
                    }
                    estimate = null;
                    requestEstimate();
                }
            }
            case LogRecord.Kind.SESSION -> {
                if ("disconnect".equals(str(d, "event")) && active) {
                    end(now, "disconnected");
                }
            }
            default -> {
            }
        }
    }

    private void onEvent(JsonObject d, long now) {
        String type = str(d, "type");
        if ("stream".equals(type)) {
            String ev = str(d, "event");
            if (ev == null) {
                return;
            }
            switch (ev) {
                case "started" -> start(now);
                case "paused" -> {
                    streamPaused = true;
                    updatePause(now);
                }
                case "resumed" -> {
                    streamPaused = false;
                    updatePause(now);
                }
                case "complete" -> end(now, "completed");
                case "canceled" -> end(now, "canceled");
                case "paused_on_error" -> {
                    streamPaused = true;
                    updatePause(now);
                }
                default -> {
                }
            }
        } else if ("interceptor".equals(type)) {
            String st = str(d, "state");
            interceptor = st != null && !"inactive".equals(st);
            if (interceptor && d.has("file_line") && d.get("file_line").isJsonPrimitive()) {
                interceptorLine = d.get("file_line").getAsInt() - CAPTURE_FILE_LINE_OFFSET;
            } else if (!interceptor) {
                interceptorLine = 0;
            }
            updatePause(now);
        }
    }

    private void start(long now) {
        active = true;
        result = null;
        startNs = now;
        endNs = 0;
        pausedNs = 0;
        pauseSinceNs = -1;
        streamPaused = false;
        interceptor = false;
        interceptorLine = 0;
        ackedLine = 0;
        lineByCmd.clear();
        samples.clear();
        updatePause(now);
    }

    private void end(long now, String why) {
        updatePauseTo(now, false);
        active = false;
        result = why;
        endNs = now;
        if ("completed".equals(why) && estimate != null) {
            ackedLine = estimate.lines();
        }
    }

    private void updatePause(long now) {
        updatePauseTo(now, active && (interceptor || hold || streamPaused));
    }

    private void updatePauseTo(long now, boolean paused) {
        if (paused && pauseSinceNs < 0) {
            pauseSinceNs = now;
        } else if (!paused && pauseSinceNs >= 0) {
            pausedNs += now - pauseSinceNs;
            pauseSinceNs = -1;
        }
    }

    private void requestEstimate() {
        if (estimate == null && file != null && cache != null) {
            estimate = cache.peek(file, sha, controller()).orElse(null);
        }
    }

    /** What the controller has reported, for estimates. */
    public synchronized EstimateCache.Controller controller() {
        return new EstimateCache.Controller(Map.copyOf(settings), plannerBlocks, wco == null ? null : wco.clone());
    }

    /** True while a stream runs (or is paused). */
    public synchronized boolean active() {
        return active;
    }

    public synchronized Estimate estimate() {
        requestEstimate();
        return estimate;
    }

    // ---------------------------------------------------------------- the prediction

    /** A point-in-time view. Times in seconds; {@code adjustedRemainingS} null without an estimate. */
    public record Snapshot(boolean active, String result, String file, double elapsedS, double pausedS,
                           double machineS, int ackedLine, int doneLine, Estimate estimate, double doneEstS,
                           double ratio, Double machineRemainingS, Double adjustedRemainingS, String predictedFinish,
                           Estimate.Op currentOp, Double opRemainingS, String currentTool,
                           Estimate.ToolChange nextToolChange, Double nextToolChangeInS, int toolChangesRemaining,
                           boolean waitingForToolChange) {

        /** Fraction of the estimated machine time done (0..1), or null. */
        public Double fraction() {
            if (estimate == null || estimate.machineS() <= 0) {
                return null;
            }
            return Math.max(0, Math.min(1, doneEstS / estimate.machineS()));
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("active", active);
            o.addProperty("result", result);
            o.addProperty("file", file);
            o.addProperty("elapsed_s", r1(elapsedS));
            o.addProperty("paused_s", r1(pausedS));
            o.addProperty("machine_elapsed_s", r1(machineS));
            o.addProperty("line_acked", ackedLine);
            o.addProperty("line_done", doneLine);
            o.addProperty("estimate_total_s", estimate == null ? null : r1(estimate.totalS()));
            o.addProperty("estimate_machine_s", estimate == null ? null : r1(estimate.machineS()));
            o.addProperty("done_estimate_s", estimate == null ? null : r1(doneEstS));
            Double f = fraction();
            o.addProperty("fraction", f == null ? null : Math.round(f * 1000) / 1000.0);
            o.addProperty("ratio", Math.round(ratio * 1000) / 1000.0);
            o.addProperty("machine_remaining_s", machineRemainingS == null ? null : r1(machineRemainingS));
            o.addProperty("adjusted_remaining_s", adjustedRemainingS == null ? null : r1(adjustedRemainingS));
            o.addProperty("predicted_finish", predictedFinish);
            o.addProperty("current_op", currentOp == null ? null : currentOp.name());
            o.addProperty("current_tool", currentTool);
            o.addProperty("op_remaining_s", opRemainingS == null ? null : r1(opRemainingS));
            if (nextToolChange != null) {
                JsonObject n = new JsonObject();
                n.addProperty("line", nextToolChange.line());
                n.addProperty("tool", "T" + nextToolChange.to());
                n.addProperty("in_s", nextToolChangeInS == null ? null : r1(nextToolChangeInS));
                o.add("next_tool_change", n);
            } else {
                o.add("next_tool_change", JsonNull.INSTANCE);
            }
            o.addProperty("tool_changes_remaining", toolChangesRemaining);
            o.addProperty("waiting_for_tool_change", waitingForToolChange);
            return o;
        }
    }

    /** The prediction now, or null when no stream has started since the bridge started. */
    public synchronized Snapshot snapshot() {
        if (startNs == 0 && !active) {
            return null;
        }
        requestEstimate();
        long now = active ? nanos.getAsLong() : endNs;
        long paused = pausedNs + (pauseSinceNs >= 0 ? now - pauseSinceNs : 0);
        double elapsed = (now - startNs) / 1e9;
        double pausedS = paused / 1e9;
        double machine = Math.max(0, elapsed - pausedS);
        int done = doneLine();
        String fileName = file == null ? null : file.getFileName().toString();
        if (estimate == null) {
            return new Snapshot(active, result, fileName, elapsed, pausedS, machine, ackedLine, done, null, 0, 1.0,
                    null, null, null, null, null, null, null, null, 0, interceptor);
        }
        Estimate e = estimate;
        double doneEst = e.atLine(done);
        double ratio = ratio(machine, doneEst);
        double machineRemaining = Math.max(0, e.machineS() - doneEst);
        int changeLine = interceptor && interceptorLine > 0 ? interceptorLine : Integer.MAX_VALUE;
        double changesAhead = 0;
        int remaining = 0;
        Estimate.ToolChange next = null;
        String tool = null;
        for (Estimate.ToolChange c : e.toolChanges()) {
            boolean current = c.line() == changeLine;
            boolean ahead = !current && c.line() > Math.max(ackedLine, done) && active;
            if (ahead) {
                remaining++;
                changesAhead += c.bitsetterS();
                if (next == null) {
                    next = c;
                }
            } else {
                tool = "T" + c.to();
            }
        }
        Double adjusted = active ? machineRemaining * ratio + changesAhead : 0.0;
        String finish = active ? OffsetDateTime.now(wall).plus(Math.round(adjusted), ChronoUnit.SECONDS)
                .truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) : null;
        Estimate.Op op = null;
        for (Estimate.Op o : e.ops()) {
            if (done + 1 >= o.firstLine() && done + 1 <= o.lastLine()) {
                op = o;
                break;
            }
        }
        if (op == null) {
            for (Estimate.Op o : e.ops()) {
                if (o.firstLine() > done) {
                    op = o;
                    break;
                }
            }
        }
        Double opRemaining = op == null ? null : Math.max(0, op.endS() - Math.max(doneEst, op.startS())) * ratio;
        Double nextIn = next == null ? null : Math.max(0, next.atS() - doneEst) * ratio;
        return new Snapshot(active, result, fileName, elapsed, pausedS, machine, ackedLine, done, e, doneEst, ratio,
                machineRemaining, adjusted, finish, op, opRemaining, tool, next, nextIn, remaining, interceptor);
    }

    /**
     * The last line known done: the acknowledged line, less up to the planner's block count of timed lines while the
     * controller is moving.
     */
    private int doneLine() {
        if (estimate == null || !active || ackedLine <= 0 || "Idle".equals(state)) {
            return ackedLine;
        }
        int blocks = plannerBlocks != null ? plannerBlocks : 15;
        double[] le = estimate.lineEndS();
        int line = Math.min(ackedLine, le.length);
        int timed = 0;
        while (line > 1 && timed < blocks) {
            double before = le[line - 2];
            if (le[line - 1] > before) {
                timed++;
            }
            line--;
        }
        return line == 1 && timed < blocks ? 0 : line;
    }

    /** Actual / estimated over the recent window (the whole run while the estimate covered is short). */
    private double ratio(double machine, double doneEst) {
        double[] last = samples.peekLast();
        if (last == null || machine - last[0] >= 5) {
            samples.addLast(new double[]{machine, doneEst});
            while (samples.size() > 2 && machine - samples.peekFirst()[0] > WINDOW_S) {
                samples.removeFirst();
            }
        }
        double r = 1.0;
        double[] first = samples.peekFirst();
        if (first != null && doneEst - first[1] >= MIN_EST_S) {
            r = (machine - first[0]) / (doneEst - first[1]);
        } else if (doneEst >= MIN_EST_S) {
            r = machine / doneEst;
        }
        return Math.max(RATIO_MIN, Math.min(RATIO_MAX, r));
    }

    // ---------------------------------------------------------------- helpers

    static double r1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }
}
