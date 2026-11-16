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
package com.willwinder.ugs.nbp.bridge.notify;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.willwinder.ugs.nbp.bridge.job.JobModel;
import com.willwinder.ugs.nbp.bridge.job.ToolInfo;
import com.willwinder.ugs.nbp.bridge.log.LogRecord.Kind;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns session-log records (which {@code Capture} builds from UGS events) into notifications.
 * Not thread safe: the notifier calls it from its single engine thread only.
 * <p>
 * Trigger toggles are applied by the caller; this class reads the config only for the feed-hold delay and the
 * progress step.
 */
final class TriggerEngine {
    private static final Logger LOGGER = Logger.getLogger(TriggerEngine.class.getName());
    /** An AlarmEvent without a wire ALARM:n within this time is reported on its own. */
    static final long UGS_ALARM_GRACE_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final Pattern T_WORD = Pattern.compile("(?i)(?<![A-Z])T\\s*(\\d{1,3})");
    private static final String TOOL_CHANGE_STEP = "toolChange.changeTool";

    record Notification(String trigger, String text, String level) {
    }

    /** Resolves "T21 Whiteside RU1600 1/8 in upcut" for an M6 line of the streamed file. */
    interface ToolLookup {
        String describe(Path file, int fileLine, Integer toolNumber);
    }

    private final Supplier<NotifyConfig> config;
    private final LongSupplier nanos;
    private final ToolLookup tools;

    // connection / stream state
    private String port;
    private boolean streaming;
    private String streamPath;
    private Integer streamLines;
    private Double streamStartMono;
    private int lastFileLine;
    private int lastProgressStep;
    private final Map<String, Integer> fileLineByCmd = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > 512;
        }
    };
    private String lastError;
    private int lastErrorLine;
    // hold
    private String holdState;
    private long holdSince;
    private boolean holdNotified;
    // alarms
    private long lastCodedAlarm = Long.MIN_VALUE / 2;
    private String pendingUgsAlarm;
    private long pendingUgsAlarmAt;

    TriggerEngine(Supplier<NotifyConfig> config, LongSupplier nanos, ToolLookup tools) {
        this.config = config;
        this.nanos = nanos;
        this.tools = tools;
    }

    /** Record kinds the engine cares about (the notifier filters before parsing). */
    static boolean relevant(String kind) {
        return switch (kind) {
            case Kind.SESSION, Kind.EVENT, Kind.FILE, Kind.TX, Kind.COMMAND -> true;
            default -> false;
        };
    }

    List<Notification> onRecord(JsonObject record) {
        List<Notification> out = new ArrayList<>();
        String kind = str(record, "kind");
        JsonObject d = record.has("data") && record.get("data").isJsonObject() ? record.getAsJsonObject("data") : new JsonObject();
        Double mono = record.has("mono") && record.get("mono").isJsonPrimitive() ? record.get("mono").getAsDouble() : null;
        if (kind == null) {
            return out;
        }
        switch (kind) {
            case Kind.SESSION -> onSession(d, out);
            case Kind.EVENT -> onEvent(d, mono, out);
            case Kind.FILE -> onFile(d, mono);
            case Kind.TX -> onTx(d, mono, out);
            case Kind.COMMAND -> onCommand(d);
            default -> {
            }
        }
        return out;
    }

    /** Timers: feed hold duration and AlarmEvents without a code. Called about once a second. */
    List<Notification> tick() {
        List<Notification> out = new ArrayList<>();
        long now = nanos.getAsLong();
        int holdSeconds = config.get().feedHoldSeconds;
        if (holdState != null && !holdNotified && holdSeconds > 0
                && now - holdSince >= TimeUnit.SECONDS.toNanos(holdSeconds)) {
            holdNotified = true;
            out.add(new Notification(Triggers.FEED_HOLD,
                    Messages.feedHold(holdState, holdSeconds, fileName(), lastFileLine), Triggers.WARN));
        }
        if (pendingUgsAlarm != null && now - pendingUgsAlarmAt >= UGS_ALARM_GRACE_NANOS) {
            if (Math.abs(pendingUgsAlarmAt - lastCodedAlarm) > UGS_ALARM_GRACE_NANOS) {
                out.add(new Notification(Triggers.ALARM, Messages.ugsAlarm(pendingUgsAlarm, fileName(), lastFileLine), Triggers.ALERT));
            }
            pendingUgsAlarm = null;
        }
        return out;
    }

    // ---------------------------------------------------------------- records

    private void onSession(JsonObject d, List<Notification> out) {
        String event = str(d, "event");
        if ("connect".equals(event)) {
            port = str(d, "port");
            clearHold();
        } else if ("disconnect".equals(event)) {
            if ("connection_closed".equals(str(d, "reason"))) {
                out.add(new Notification(Triggers.CONNECTION_LOST,
                        Messages.connectionLost(port, streaming ? fileName() : null, lastFileLine), Triggers.ALERT));
            }
            streaming = false;
            clearHold();
        }
    }

    private void onEvent(JsonObject d, Double mono, List<Notification> out) {
        String type = str(d, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "stream" -> onStream(str(d, "event"), mono, out);
            case "alarm" -> {
                if (d.has("code") && d.get("code").isJsonPrimitive()) {
                    lastCodedAlarm = nanos.getAsLong();
                    pendingUgsAlarm = null;
                    out.add(new Notification(Triggers.ALARM,
                            Messages.alarm(d.get("code").getAsInt(), streaming ? fileName() : null, lastFileLine), Triggers.ALERT));
                } else if (d.has("ugs_alarm")) {
                    long now = nanos.getAsLong();
                    if (now - lastCodedAlarm > UGS_ALARM_GRACE_NANOS) {
                        pendingUgsAlarm = str(d, "ugs_alarm");
                        pendingUgsAlarmAt = now;
                    }
                }
            }
            case "controller_state" -> {
                String state = str(d, "state");
                if ("hold".equals(state) || "door".equals(state)) {
                    if (holdState == null) {
                        holdSince = nanos.getAsLong();
                        holdNotified = false;
                    }
                    holdState = state;
                } else {
                    clearHold();
                }
            }
            case "interceptor" -> onInterceptor(d, out);
            default -> {
            }
        }
    }

    private void onStream(String event, Double mono, List<Notification> out) {
        if (event == null) {
            return;
        }
        switch (event) {
            case "started" -> {
                streaming = true;
                streamStartMono = mono;
                streamPath = null;
                streamLines = null;
                lastFileLine = 0;
                lastProgressStep = 0;
                lastError = null;
            }
            case "paused_on_error" -> {
                String err = lastError;
                int line = lastErrorLine > 0 ? lastErrorLine : lastFileLine;
                out.add(new Notification(Triggers.STREAM_ERROR, Messages.streamError(fileName(), line, err), Triggers.ALERT));
            }
            case "complete" -> {
                if (streaming) {
                    Double secs = streamStartMono != null && mono != null ? mono - streamStartMono : null;
                    out.add(new Notification(Triggers.STREAM_FINISHED,
                            Messages.streamFinished(fileName(), secs, streamLines), Triggers.INFO));
                }
                streaming = false;
            }
            case "canceled" -> streaming = false;
            default -> {
            }
        }
    }

    private void onFile(JsonObject d, Double mono) {
        if ("stream_start".equals(str(d, "action"))) {
            streamPath = str(d, "path");
            streamLines = d.has("lines") && d.get("lines").isJsonPrimitive() ? d.get("lines").getAsInt() : null;
            if (!streaming) { // stream event missed (bridge started late): treat the file record as the start
                streaming = true;
                streamStartMono = mono;
                lastFileLine = 0;
                lastProgressStep = 0;
            }
        }
    }

    private void onTx(JsonObject d, Double mono, List<Notification> out) {
        if (!d.has("file_line") || !d.get("file_line").isJsonPrimitive()) {
            return;
        }
        int line = d.get("file_line").getAsInt();
        String cmdId = str(d, "cmd_id");
        if (cmdId != null) {
            fileLineByCmd.put(cmdId, line);
        }
        lastFileLine = Math.max(lastFileLine, line);
        int every = config.get().progressEveryPercent;
        if (!streaming || every <= 0 || streamLines == null || streamLines <= 0) {
            return;
        }
        int percent = (int) Math.min(100, (long) lastFileLine * 100 / streamLines);
        int step = percent / every;
        if (step > lastProgressStep && percent < 100) {
            lastProgressStep = step;
            int shown = step * every;
            Double elapsed = streamStartMono != null && mono != null ? mono - streamStartMono : null;
            out.add(new Notification(Triggers.PROGRESS,
                    Messages.progress(shown, fileName(), lastFileLine, streamLines, elapsed), Triggers.INFO));
        }
    }

    private void onCommand(JsonObject d) {
        String state = str(d, "state");
        if (!"error".equals(state) && !"alarm".equals(state)) {
            return;
        }
        StringBuilder e = new StringBuilder();
        JsonElement resp = d.get("response");
        if (resp != null && resp.isJsonArray() && !resp.getAsJsonArray().isEmpty()) {
            e.append(resp.getAsJsonArray().get(resp.getAsJsonArray().size() - 1).getAsString());
        } else if (d.has("error_code")) {
            e.append("error:").append(d.get("error_code").getAsInt());
        }
        String line = str(d, "line");
        if (line != null) {
            e.append(e.length() > 0 ? " on " : "").append(line.length() > 60 ? line.substring(0, 60) + "…" : line);
        }
        lastError = e.toString();
        Integer fl = fileLineByCmd.get(str(d, "id"));
        lastErrorLine = fl == null ? 0 : fl;
    }

    private void onInterceptor(JsonObject d, List<Notification> out) {
        String state = str(d, "state");
        String interceptor = str(d, "interceptor");
        boolean toolChange = interceptor == null || interceptor.contains("ToolChange");
        if (!toolChange) {
            return;
        }
        int line = d.has("file_line") && d.get("file_line").isJsonPrimitive() ? d.get("file_line").getAsInt() : lastFileLine;
        if ("waiting_for_user".equals(state)) {
            String step = str(d, "step");
            if (step != null && !TOOL_CHANGE_STEP.equals(step)) {
                return; // the second prompt ("continue") needs no extra message
            }
            Integer tool = toolNumber(str(d, "trigger"));
            String desc = null;
            try {
                desc = tools == null || streamPath == null ? null : tools.describe(Paths.get(streamPath), line, tool);
            } catch (RuntimeException e) {
                LOGGER.log(Level.FINE, "Tool lookup failed", e);
            }
            if (desc == null && tool != null) {
                desc = "T" + tool;
            }
            out.add(new Notification(Triggers.TOOL_CHANGE_WAITING, Messages.toolChange(desc, fileName(), line), Triggers.WARN));
        } else if ("failed".equals(state)) {
            out.add(new Notification(Triggers.TOOL_CHANGE_WAITING, Messages.toolChangeFailed(fileName(), line), Triggers.ALERT));
        }
    }

    // ---------------------------------------------------------------- helpers

    private void clearHold() {
        holdState = null;
        holdNotified = false;
    }

    private String fileName() {
        if (streamPath == null) {
            return null;
        }
        Path p = Paths.get(streamPath).getFileName();
        return p == null ? streamPath : p.toString();
    }

    static Integer toolNumber(String trigger) {
        if (trigger == null) {
            return null;
        }
        Matcher m = T_WORD.matcher(trigger);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    /** Looks the tool up in the job's headers (cached per file). */
    static final class JobModelTools implements ToolLookup {
        private Path cachedPath;
        private JobModel cached;

        @Override
        public String describe(Path file, int fileLine, Integer toolNumber) {
            JobModel model = model(file);
            if (model == null) {
                return null;
            }
            // the T word of the trigger command wins: file_line counts lines of the processed stream, which may
            // differ from the original file's numbering
            int n = toolNumber != null ? toolNumber : fileLine > 0 ? model.toolChangeTarget(fileLine) : -1;
            if (n <= 0) {
                return null;
            }
            ToolInfo t = model.tools().get(n);
            String id = model.toolId(n);
            if (t == null || t.name() == null || t.name().isBlank()) {
                return id;
            }
            return id + " " + t.name();
        }

        private JobModel model(Path file) {
            if (file == null) {
                return null;
            }
            if (!file.equals(cachedPath)) {
                cachedPath = file;
                try {
                    cached = JobModel.load(file);
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "Could not read " + file + " for tool names", e);
                    cached = null;
                }
            }
            return cached;
        }
    }
}
