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
import com.willwinder.ugs.nbp.bridge.job.JobModel;
import com.willwinder.ugs.nbp.bridge.job.JobState;
import com.willwinder.ugs.nbp.bridge.job.JournalWriter;
import com.willwinder.ugs.nbp.bridge.job.Operation;
import com.willwinder.ugs.nbp.bridge.job.PlannerModel;
import com.willwinder.ugs.nbp.bridge.log.LogRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps the job journal ({@code <session>/job-<sha>.state.json}, SPEC §11.6) while a file streams, and writes the
 * {@code job} log records (started, op_start, paused, stopped, completed).
 *
 * <ul>
 *   <li>{@code line_acked}: highest file line whose command completed ({@code ok}; skipped lines count too).</li>
 *   <li>{@code line_done} (conservative): GRBL acknowledges a line when it enters the planner, so up to
 *       {@link PlannerModel#plannerBlocks()} acknowledged motion lines may still be queued. {@code line_done} is
 *       the acknowledged motion line {@code plannerBlocks + 1} back; it catches up to {@code line_acked} when the
 *       controller reports Idle with nothing awaiting an answer.</li>
 *   <li>{@code last_mpos}, {@code wco} from status reports; {@code operation}/{@code tool} from the job model (the
 *       tool in the collet, once the bridge knows it, wins); {@code modal} from the job model at the acknowledged
 *       line; {@code bitsetter_ref} from the runtime.</li>
 * </ul>
 * Written through {@link JournalWriter}: at most once per {@code minIntervalMs} while running, immediately when the
 * stream state changes; {@link #tick()} flushes. Not thread-safe: the runtime calls it from its event thread.
 */
public final class JournalTracker {
    private static final Logger LOGGER = Logger.getLogger(JournalTracker.class.getName());

    private final Records records;
    private final Clock clock;
    private final long minIntervalMs;
    private volatile PlannerModel planner;
    private JobModel job;
    private JobState state;
    private JournalWriter writer;
    private final Deque<Integer> ackedMotion = new ArrayDeque<>();
    private String lastOp;

    public JournalTracker(Records records, Clock clock, long minIntervalMs, PlannerModel planner) {
        this.records = records;
        this.clock = clock;
        this.minIntervalMs = minIntervalMs;
        this.planner = planner;
    }

    /** The planner model used for {@code line_done} (the controller's {@code $I} can refine it after connect). */
    public void setPlanner(PlannerModel planner) {
        if (planner != null) {
            this.planner = planner;
        }
    }

    public PlannerModel planner() {
        return planner;
    }

    /** True while a journal is open (from stream start until the next start). */
    public boolean active() {
        return state != null;
    }

    public boolean running() {
        return state != null && ("running".equals(state.stream) || "paused".equals(state.stream));
    }

    public JobModel job() {
        return job;
    }

    public Path path() {
        return writer == null ? null : writer.path();
    }

    /** A copy of the journal as it stands, or null. */
    public JobState snapshot() {
        return state == null ? null : state.copy();
    }

    /**
     * Stream started.
     *
     * @param wco  current G54 (mm) or null
     * @param mpos current machine position or null
     * @param tool tool in the collet if known, else null (the job model's tool is used)
     */
    public void start(JobModel job, Path sessionDir, String session, double[] wco, double[] mpos, String tool, Double ref) {
        this.job = job;
        Instant now = Instant.ofEpochMilli(clock.millis());
        state = JobState.start(job, session, now);
        state.wco = BitSetter.round3(wco);
        state.lastMpos = BitSetter.round3(mpos);
        state.tool = tool;
        fromJob = tool == null;
        state.bitsetterRef = ref == null ? null : BitSetter.round3(ref);
        state.modal = job.modalAt(1).toJournal();
        ackedMotion.clear();
        lastOp = null;
        writer = new JournalWriter(JobState.pathIn(sessionDir, job.sha256()), clock, minIntervalMs);
        JsonObject d = jobData("started", null);
        d.addProperty("path", state.file.path);
        d.addProperty("lines", job.lineCount());
        d.addProperty("journal", writer.path().toString());
        records.append(LogRecord.Kind.JOB, LogRecord.Src.UGS, d);
        write(true);
    }

    /** A file line's command completed (or was skipped). */
    public void onAck(int fileLine) {
        if (state == null || fileLine < 1 || fileLine > job.lineCount() || !running()) {
            return;
        }
        if (fileLine > state.lineAcked) {
            state.lineAcked = fileLine;
            if (job.motion(fileLine) != JobModel.Motion.NONE) {
                ackedMotion.addLast(fileLine);
                int keep = planner.plannerBlocks() + 1;
                while (ackedMotion.size() > keep) {
                    int done = ackedMotion.removeFirst();
                    state.lineDone = Math.max(state.lineDone, done);
                }
            }
            state.modal = job.modalAt(Math.min(fileLine + 1, job.lineCount() + 1)).toJournal();
            Operation op = job.operationAt(fileLine).orElse(null);
            if (op != null) {
                if (!Objects.equals(op.name(), lastOp)) {
                    lastOp = op.name();
                    JsonObject d = jobData("op_start", op.name());
                    d.addProperty("line", fileLine);
                    d.addProperty("op_index", op.index());
                    d.addProperty("tool", op.tool());
                    records.append(LogRecord.Kind.JOB, LogRecord.Src.UGS, d);
                }
                state.operation = op.name();
            }
            if (state.tool == null || fromJob) {
                // tool at the start of the acknowledged line: an M6 line itself doesn't count until a later line
                // is acknowledged, i.e. after the tool change ran
                int t = job.toolAt(fileLine);
                if (t > 0) {
                    state.tool = job.toolId(t);
                    fromJob = true;
                }
            }
            write(false);
        }
    }

    private boolean fromJob;

    /** The tool in the collet as set by a tool change or the operator (wins over the job model). */
    public void setTool(String tool) {
        if (state != null && tool != null) {
            state.tool = tool;
            fromJob = false;
            write(false);
        }
    }

    public void setReference(Double ref) {
        if (state != null) {
            state.bitsetterRef = ref == null ? null : BitSetter.round3(ref);
            write(false);
        }
    }

    public void setOriginId(String id) {
        if (state != null) {
            state.originId = id;
            write(false);
        }
    }

    /**
     * A status report.
     *
     * @param idleAndDrained the controller is Idle and no command is awaiting an answer (everything acked ran)
     */
    public void onStatus(double[] mpos, double[] wco, boolean idleAndDrained) {
        if (state == null || !running()) {
            return;
        }
        if (mpos != null) {
            state.lastMpos = BitSetter.round3(mpos);
        }
        if (wco != null) {
            state.wco = BitSetter.round3(wco);
        }
        if (idleAndDrained && state.lineDone < state.lineAcked) {
            state.lineDone = state.lineAcked;
            ackedMotion.clear();
        }
        write(false);
    }

    public void onPaused() {
        transition("paused", "paused", null);
    }

    public void onResumed() {
        if (state != null && "paused".equals(state.stream)) {
            state.stream = "running";
            write(true);
        }
    }

    /** Stream canceled, or the connection dropped. */
    public void onStopped(String reason) {
        transition("stopped", "stopped", reason);
    }

    public void onAlarm(String alarm) {
        transition("alarm", "stopped", alarm == null ? "alarm" : "alarm: " + alarm);
    }

    public void onCompleted() {
        if (state == null || !running()) {
            return;
        }
        state.lineAcked = job.lineCount();
        state.lineDone = job.lineCount();
        transition("completed", "completed", null);
    }

    private void transition(String stream, String event, String reason) {
        if (state == null || !running()) {
            return;
        }
        if (Objects.equals(state.stream, stream)) {
            return;
        }
        state.stream = stream;
        JsonObject d = jobData(event, state.operation);
        d.addProperty("line", state.lineAcked);
        d.addProperty("line_done", state.lineDone);
        if (reason != null) {
            d.addProperty("reason", reason);
        }
        records.append(LogRecord.Kind.JOB, LogRecord.Src.UGS, d);
        write(true);
    }

    /** Writes pending changes (call about once a second). */
    public void tick() {
        if (writer != null) {
            try {
                writer.flush();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Could not write the job journal", e);
            }
        }
    }

    private void write(boolean now) {
        try {
            writer.update(state);
            if (now) {
                writer.flush();
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not write the job journal " + writer.path(), e);
        }
    }

    private JsonObject jobData(String event, String op) {
        JsonObject d = new JsonObject();
        d.addProperty("event", event);
        d.addProperty("file_sha256", job.sha256());
        d.add("line", com.google.gson.JsonNull.INSTANCE);
        d.addProperty("op", op);
        return d;
    }

    /**
     * Launch-time crash check: a journal that still says {@code running} or {@code paused} belongs to a UGS that
     * didn't stop the stream (crash, kill, power cut). It is rewritten as {@code crashed} and a {@code job stopped}
     * record (reason {@code crashed}) is logged.
     *
     * @return the journal (rewritten if it was running), or null when the file doesn't exist or can't be read
     */
    public static JobState recover(Path journal, Records records, Clock clock) {
        if (journal == null || !Files.exists(journal)) {
            return null;
        }
        try {
            JobState s = JobState.read(journal);
            if ("running".equals(s.stream) || "paused".equals(s.stream)) {
                String was = s.stream;
                s.stream = "crashed";
                s.touch(Instant.ofEpochMilli(clock.millis()));
                s.write(journal);
                JsonObject d = new JsonObject();
                d.addProperty("event", "stopped");
                d.addProperty("file_sha256", s.file.sha256);
                d.addProperty("line", s.lineAcked);
                d.addProperty("line_done", s.lineDone);
                d.addProperty("op", s.operation);
                d.addProperty("reason", "crashed");
                d.addProperty("was", was);
                d.addProperty("journal", journal.toString());
                records.append(LogRecord.Kind.JOB, LogRecord.Src.BRIDGE, d);
            }
            return s;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not read the job journal " + journal, e);
            return null;
        }
    }
}
