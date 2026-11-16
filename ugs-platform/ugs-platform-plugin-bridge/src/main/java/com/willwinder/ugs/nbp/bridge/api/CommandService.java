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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.willwinder.ugs.nbp.bridge.log.LogRecord;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Claude's command queue ({@code POST /command}).
 * <p>
 * One worker sends the lines of all requests in order, one at a time: a line goes to UGS only after the previous
 * one has finished. That keeps UGS's own queue short, lets the bridge drop lines that haven't been sent yet
 * (lock, alarm, reset, disconnect, authority expiry) and stops a request at its first error. {@code $H}/{@code $X}
 * wait for the operator's dialog ({@code awaiting_operator}). Results are kept for the last
 * {@value #KEEP_RESULTS} requests.
 * <p>
 * Log records: this class writes {@code command} records for {@code queued}, {@code awaiting_operator} and
 * {@code dropped}; {@code sent} and the final state of each line come from the capture (the line is registered
 * with {@code CommandOrigins}). The {@code ?} line never goes through UGS's queue: it is answered with a fresh
 * status report, and both its records are written here.
 */
public final class CommandService {
    private static final Logger LOGGER = Logger.getLogger(CommandService.class.getName());
    static final int KEEP_RESULTS = 500;
    static final int DEFAULT_TIMEOUT_S = 60;

    /** One line of a request. Mutable; guarded by the owning {@link Req}. */
    static final class Line {
        final int index;
        final String raw;
        final Classifier.Classification cls;
        final String id;
        String state = "queued";
        List<String> response = new ArrayList<>();
        Integer errorCode;
        Integer alarmCode;

        Line(int index, String raw, Classifier.Classification cls, String id) {
            this.index = index;
            this.raw = raw;
            this.cls = cls;
            this.id = id;
        }

        boolean unsent() {
            return state.equals("queued") || state.equals("awaiting_operator");
        }
    }

    /** One {@code POST /command} request. */
    static final class Req {
        final String id;
        final String tag;
        final String expect;
        final long timeoutMs;
        final List<Line> lines = new ArrayList<>();
        final Consumer<Req> onDone;
        String reason;
        boolean finished;

        Req(String id, String tag, String expect, long timeoutMs, Consumer<Req> onDone) {
            this.id = id;
            this.tag = tag;
            this.expect = expect;
            this.timeoutMs = timeoutMs;
            this.onDone = onDone;
        }

        synchronized String state() {
            boolean waiting = false;
            boolean running = false;
            boolean dropped = false;
            boolean failed = false;
            for (Line l : lines) {
                switch (l.state) {
                    case "awaiting_operator" -> waiting = true;
                    case "queued", "sent" -> running = true;
                    case "dropped" -> dropped = true;
                    case "ok" -> {
                    }
                    default -> failed = true; // error, alarm, skipped
                }
            }
            if (waiting) {
                return "awaiting_operator";
            }
            if (running) {
                return "running";
            }
            if (failed) {
                return "failed";
            }
            return dropped ? "dropped" : "done";
        }
    }

    private final SessionLog log;
    private final MachineLink link;
    private final Authority authority;
    private final OperatorPrompts prompts;
    private final Supplier<List<String>> statusQuery;
    private final AtomicInteger counter = new AtomicInteger();
    private final LinkedBlockingDeque<Req> queue = new LinkedBlockingDeque<>();
    private final Map<String, Req> results = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Req> eldest) {
            return size() > KEEP_RESULTS;
        }
    };
    private final Thread worker;
    private volatile boolean stopped;
    private volatile OperatorPrompts.Prompt openPrompt;
    private volatile Req current;

    /**
     * @param statusQuery answers the {@code ?} line: requests a status report and returns the raw report line(s)
     */
    public CommandService(SessionLog log, MachineLink link, Authority authority, OperatorPrompts prompts,
                          Supplier<List<String>> statusQuery) {
        this.log = log;
        this.link = link;
        this.authority = authority;
        this.prompts = prompts;
        this.statusQuery = statusQuery;
        this.worker = new Thread(this::run, "ugs-bridge-claude-commands");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public void stop() {
        stopped = true;
        dropAll("bridge stopped");
        worker.interrupt();
    }

    /**
     * Queues a request. The caller has validated the lines and checked the level.
     *
     * @return the request, already logged as {@code queued}
     */
    Req submit(List<String> lines, List<Classifier.Classification> classes, String tag, String expect, Integer timeoutS,
               Consumer<Req> onDone) {
        String id = "c-" + counter.incrementAndGet();
        int t = timeoutS == null ? DEFAULT_TIMEOUT_S : Math.max(1, Math.min(timeoutS, 3600));
        Req req = new Req(id, tag, expect, TimeUnit.SECONDS.toMillis(t), onDone);
        synchronized (req) {
            for (int i = 0; i < lines.size(); i++) {
                Line l = new Line(i, lines.get(i).strip(), classes.get(i), id + "-" + i);
                req.lines.add(l);
                logLine(req, l, "queued", null);
            }
        }
        synchronized (results) {
            results.put(id, req);
        }
        queue.add(req);
        return req;
    }

    /** The request, or null if unknown (or too old). */
    Req find(String id) {
        synchronized (results) {
            return results.get(id);
        }
    }

    /** {@code CommandResult} JSON. */
    static JsonObject toJson(Req req) {
        JsonObject o = new JsonObject();
        synchronized (req) {
            o.addProperty("id", req.id);
            o.addProperty("tag", req.tag);
            o.addProperty("expect", req.expect);
            o.addProperty("state", req.state());
            if (req.reason != null) {
                o.addProperty("reason", req.reason);
            }
            JsonArray arr = new JsonArray();
            for (Line l : req.lines) {
                JsonObject j = new JsonObject();
                j.addProperty("index", l.index);
                j.addProperty("line", l.raw);
                j.addProperty("class", l.cls.cls().id());
                j.addProperty("state", l.state);
                JsonArray resp = new JsonArray();
                l.response.forEach(resp::add);
                j.add("response", resp);
                j.addProperty("error_code", l.errorCode);
                j.addProperty("alarm_code", l.alarmCode);
                arr.add(j);
            }
            o.add("lines", arr);
        }
        return o;
    }

    /** Drops every line that hasn't been sent yet (lock, alarm, reset, disconnect). */
    public void dropAll(String reason) {
        dropWhere(reason, null);
    }

    /** Drops the unsent rest of every request that needs more than {@code level} (authority went down). */
    public void dropAbove(Level level, String reason) {
        dropWhere(reason, level);
    }

    private void dropWhere(String reason, Level level) {
        List<Req> all = new ArrayList<>(queue);
        Req cur = current;
        if (cur != null && !all.contains(cur)) {
            all.add(cur);
        }
        boolean cancelPrompt = false;
        for (Req r : all) {
            synchronized (r) {
                boolean hit = level == null || r.lines.stream().anyMatch(l -> l.unsent() && !level.allows(l.cls.required()));
                if (!hit) {
                    continue;
                }
                for (Line l : r.lines) {
                    if (l.unsent()) {
                        if (l.state.equals("awaiting_operator")) {
                            cancelPrompt = true;
                        }
                        drop(r, l, reason);
                    }
                }
                if (r.reason == null) {
                    r.reason = reason;
                }
            }
        }
        OperatorPrompts.Prompt p = openPrompt;
        if (cancelPrompt && p != null) {
            p.cancel();
        }
    }

    private void drop(Req r, Line l, String reason) {
        l.state = "dropped";
        logLine(r, l, "dropped", reason);
    }

    // ------------------------------------------------------------------ worker

    private void run() {
        while (!stopped) {
            Req req;
            try {
                req = queue.takeFirst();
            } catch (InterruptedException e) {
                return;
            }
            current = req;
            try {
                process(req);
            } catch (RuntimeException e) {
                LOGGER.log(java.util.logging.Level.WARNING, "command request " + req.id + " failed", e);
                synchronized (req) {
                    for (Line l : req.lines) {
                        if (l.unsent()) {
                            drop(req, l, "internal error");
                        }
                    }
                }
            } finally {
                current = null;
                finish(req);
            }
        }
    }

    private void finish(Req req) {
        synchronized (req) {
            if (req.finished) {
                return;
            }
            req.finished = true;
        }
        if (req.onDone != null) {
            try {
                req.onDone.accept(req);
            } catch (RuntimeException e) {
                LOGGER.log(java.util.logging.Level.WARNING, "onDone failed", e);
            }
        }
    }

    private void process(Req req) {
        long deadline = System.currentTimeMillis() + req.timeoutMs;
        for (Line line : req.lines) {
            synchronized (req) {
                if (!line.unsent()) {
                    continue; // dropped meanwhile
                }
                String refusal = refusal(line);
                if (refusal != null) {
                    dropRest(req, line, refusal);
                    return;
                }
            }
            if (line.cls.needsOperator() && !askOperator(req, line)) {
                return;
            }
            if (!sendLine(req, line, deadline)) {
                return;
            }
        }
    }

    /** Why this line may not be sent now, or null. Caller holds the request's monitor. */
    private String refusal(Line line) {
        if (authority.isLocked()) {
            return "locked by the operator";
        }
        if (!authority.level().allows(line.cls.required())) {
            return "authority is now " + authority.level().id();
        }
        if (!link.isConnected()) {
            return "not connected";
        }
        return null;
    }

    private boolean askOperator(Req req, Line line) {
        synchronized (req) {
            line.state = "awaiting_operator";
            logLine(req, line, "awaiting_operator", null);
        }
        String what = line.cls.line().equals("$H") ? "home the machine ($H)" : "clear the alarm lock ($X)";
        String msg = "Claude asks to " + what + ".\n\nRequest " + req.id + (req.tag != null ? " (" + req.tag + ")" : "")
                + (req.expect != null ? "\nExpected: " + req.expect : "")
                + "\n\nMake sure the machine is clear before you allow it.";
        OperatorPrompts.Prompt p = prompts.confirm("Claude: " + line.cls.line(), msg, "Allow " + line.cls.line());
        openPrompt = p;
        boolean approved;
        try {
            approved = Boolean.TRUE.equals(p.result().get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            approved = false;
        } catch (ExecutionException e) {
            approved = false;
        } finally {
            openPrompt = null;
        }
        synchronized (req) {
            if (!line.state.equals("awaiting_operator")) {
                return false; // dropped while waiting (lock, alarm, ...)
            }
            if (!approved) {
                dropRest(req, line, "operator declined " + line.cls.line());
                return false;
            }
            String refusal = refusal(line);
            if (refusal != null) {
                dropRest(req, line, refusal);
                return false;
            }
            line.state = "queued"; // approved: goes out as a normal line
        }
        return true;
    }

    private boolean sendLine(Req req, Line line, long deadline) {
        if (line.cls.line().equals("?")) {
            List<String> resp = statusQuery.get();
            synchronized (req) {
                line.response = new ArrayList<>(resp);
                line.state = resp.isEmpty() ? "error" : "ok";
                logLine(req, line, line.state, resp.isEmpty() ? "no status report" : null);
                if (resp.isEmpty()) {
                    dropRest(req, null, "status query failed");
                    return false;
                }
            }
            return true;
        }
        synchronized (req) {
            line.state = "sent";
        }
        MachineLink.LineResult result;
        try {
            long left = Math.max(1, deadline - System.currentTimeMillis());
            result = link.send(line.raw, line.id, LogRecord.Src.CLAUDE, req.tag).get(left, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            synchronized (req) {
                line.response.add("(timeout: no reply within " + req.timeoutMs / 1000 + " s)");
                line.state = "error";
                dropRest(req, null, "timeout");
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            synchronized (req) {
                String why = e.getCause() == null ? "send failed" : e.getCause().getMessage();
                line.response.add("(not sent: " + why + ")");
                line.state = "dropped";
                logLine(req, line, "dropped", "not sent: " + why);
                dropRest(req, null, "previous line not sent");
            }
            return false;
        }
        synchronized (req) {
            line.state = result.state();
            line.response = new ArrayList<>(result.response());
            line.errorCode = result.errorCode();
            line.alarmCode = result.alarmCode();
            if (!result.ok()) {
                dropRest(req, null, "line " + line.index + " " + result.state());
                return false;
            }
        }
        return true;
    }

    /** Drops {@code from} (if given and unsent) and every unsent line after it. Caller holds the monitor. */
    private void dropRest(Req req, Line from, String reason) {
        if (req.reason == null) {
            req.reason = reason;
        }
        for (Line l : req.lines) {
            if (l.unsent() && (from == null || l.index >= from.index)) {
                drop(req, l, reason);
            }
        }
    }

    private void logLine(Req req, Line l, String state, String reason) {
        JsonObject d = new JsonObject();
        d.addProperty("id", l.id);
        d.addProperty("origin", LogRecord.Src.CLAUDE);
        d.addProperty("line", l.raw);
        d.addProperty("state", state);
        d.addProperty("class", l.cls.cls().id());
        if (req.tag != null) {
            d.addProperty("tag", req.tag);
        }
        if (req.expect != null) {
            d.addProperty("expect", req.expect);
        }
        if (reason != null) {
            d.addProperty("reason", reason);
        }
        if (state.equals("ok") || state.equals("error")) {
            JsonArray resp = new JsonArray();
            l.response.forEach(resp::add);
            d.add("response", resp);
        }
        log.append(LogRecord.Kind.COMMAND, LogRecord.Src.CLAUDE, d);
    }
}
