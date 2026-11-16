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
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.willwinder.ugs.nbp.bridge.log.LogRecord;
import com.willwinder.universalgcodesender.types.GrblSettingMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Machine state for {@code GET /status}, {@code /settings} and {@code /file}, derived from the session log records
 * (so the API reports exactly what the log says). Fed by a {@link com.willwinder.ugs.nbp.bridge.log.SessionLog}
 * listener; must not block.
 * <ul>
 *     <li>homed this connection: a {@code $H} command finishing {@code ok} (from anyone); cleared on connect,
 *     disconnect, a controller reset (welcome line) and alarms that lose position (all but 4 and 5, probe
 *     failures).</li>
 *     <li>settings: the {@code $n=v} lines received up to the next {@code ok} (normally the reply to {@code $$}).</li>
 * </ul>
 */
public final class StateTracker implements Consumer<LogRecord> {
    /** Machine events other parts react to (authority expiry, dropping queued Claude commands). */
    public enum Trigger { ALARM, RESET, DISCONNECT, CONNECT }

    private static final Pattern SETTING_LINE = Pattern.compile("^\\$\\d+=.*");
    private static final Pattern WELCOME = Pattern.compile("^(Grbl|GrblHAL)\\s.*", Pattern.CASE_INSENSITIVE);
    static final Map<Integer, String> ALARMS = Map.of(
            1, "hard limit triggered; position is lost, re-home",
            2, "soft limit: the target is outside the machine travel",
            3, "reset while moving; position may be lost, re-home",
            4, "probe was already triggered before the probe move started",
            5, "probe didn't make contact within the search distance",
            6, "homing failed: reset during the homing cycle",
            7, "homing failed: safety door opened during homing",
            8, "homing failed: couldn't clear the limit switch on pull-off",
            9, "homing failed: limit switch not found within the search distance",
            10, "homing failed: second dual-axis limit switch not found");

    private final List<java.util.function.BiConsumer<Trigger, String>> triggers = new CopyOnWriteArrayList<>();

    // guarded by this
    private boolean connected;
    private String port;
    private String driver;
    private String firmware;
    private String session;
    private JsonObject controller;
    private long statusSeq;
    private boolean homed;
    private JsonObject alarm;
    private String streamState = "idle";
    private String streamFile;
    private String streamSha;
    private Integer lineSent;
    private Integer linesTotal;
    private JsonObject interceptor;
    private final List<String> pendingSettings = new ArrayList<>();
    private List<String> settings = List.of();
    private String settingsReadAt;
    private JsonObject file;

    public void addTriggerListener(java.util.function.BiConsumer<Trigger, String> l) {
        triggers.add(l);
    }

    @Override
    public void accept(LogRecord r) {
        switch (r.kind()) {
            case LogRecord.Kind.STATUS, LogRecord.Kind.SESSION, LogRecord.Kind.EVENT, LogRecord.Kind.COMMAND,
                 LogRecord.Kind.FILE, LogRecord.Kind.RX, LogRecord.Kind.TX -> {
                JsonObject rec;
                try {
                    rec = JsonParser.parseString(r.json()).getAsJsonObject();
                } catch (RuntimeException e) {
                    return;
                }
                JsonObject data = rec.has("data") && rec.get("data").isJsonObject() ? rec.getAsJsonObject("data") : new JsonObject();
                String t = str(rec, "t");
                List<Object[]> fire = new ArrayList<>();
                synchronized (this) {
                    apply(r, data, t, fire);
                    if (r.kind().equals(LogRecord.Kind.STATUS)) {
                        notifyAll();
                    }
                }
                for (Object[] f : fire) {
                    for (var l : triggers) {
                        l.accept((Trigger) f[0], (String) f[1]);
                    }
                }
            }
            default -> {
            }
        }
    }

    private void apply(LogRecord r, JsonObject d, String t, List<Object[]> fire) {
        switch (r.kind()) {
            case LogRecord.Kind.STATUS -> {
                JsonObject c = d.deepCopy();
                c.remove("heartbeat");
                controller = c;
                statusSeq = r.seq();
                String state = str(c, "state");
                if (alarm != null && state != null && !"Alarm".equals(state)) {
                    alarm = null;
                }
            }
            case LogRecord.Kind.SESSION -> {
                String ev = str(d, "event");
                if ("connect".equals(ev)) {
                    connected = true;
                    port = str(d, "port");
                    driver = str(d, "driver");
                    firmware = str(d, "firmware");
                    session = r.session();
                    resetMachine();
                    fire.add(new Object[]{Trigger.CONNECT, "connected"});
                } else if ("disconnect".equals(ev)) {
                    connected = false;
                    resetMachine();
                    fire.add(new Object[]{Trigger.DISCONNECT, "disconnected"});
                }
            }
            case LogRecord.Kind.EVENT -> applyEvent(d, fire);
            case LogRecord.Kind.COMMAND -> {
                String line = str(d, "line");
                if ("ok".equals(str(d, "state")) && line != null && "$H".equalsIgnoreCase(line.replace(" ", ""))) {
                    homed = true;
                }
            }
            case LogRecord.Kind.FILE -> {
                String action = str(d, "action");
                if ("loaded".equals(action)) {
                    JsonObject f = new JsonObject();
                    f.add("path", d.get("path"));
                    f.add("sha256", d.get("sha256"));
                    f.add("lines", d.get("lines"));
                    f.addProperty("loaded_at", t);
                    file = f;
                } else if ("stream_start".equals(action)) {
                    streamFile = str(d, "path");
                    streamSha = str(d, "sha256");
                    linesTotal = d.has("lines") && d.get("lines").isJsonPrimitive() ? d.get("lines").getAsInt() : null;
                    lineSent = null;
                }
            }
            case LogRecord.Kind.TX -> {
                if (d.has("file_line") && d.get("file_line").isJsonPrimitive()) {
                    lineSent = d.get("file_line").getAsInt();
                }
                if ("$$".equals(str(d, "line"))) {
                    pendingSettings.clear();
                }
            }
            case LogRecord.Kind.RX -> {
                String line = str(d, "line");
                if (line == null) {
                    return;
                }
                if (SETTING_LINE.matcher(line).matches()) {
                    pendingSettings.add(line);
                } else if (line.equals("ok") || line.startsWith("error:")) {
                    if (!pendingSettings.isEmpty()) {
                        settings = List.copyOf(pendingSettings);
                        settingsReadAt = t;
                        pendingSettings.clear();
                    }
                } else if (WELCOME.matcher(line).matches()) {
                    homed = false;
                    pendingSettings.clear();
                    fire.add(new Object[]{Trigger.RESET, "controller reset"});
                }
            }
            default -> {
            }
        }
    }

    private void applyEvent(JsonObject d, List<Object[]> fire) {
        String type = str(d, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "alarm" -> {
                if (d.has("code") && d.get("code").isJsonPrimitive()) {
                    int code = d.get("code").getAsInt();
                    JsonObject a = new JsonObject();
                    a.addProperty("code", code);
                    a.addProperty("text", ALARMS.getOrDefault(code, "alarm " + code));
                    alarm = a;
                    if (code != 4 && code != 5) {
                        homed = false;
                    }
                    fire.add(new Object[]{Trigger.ALARM, "ALARM:" + code});
                } else if (alarm == null) {
                    fire.add(new Object[]{Trigger.ALARM, "alarm (" + str(d, "ugs_alarm") + ")"});
                }
            }
            case "stream" -> {
                String ev = str(d, "event");
                if (ev != null) {
                    streamState = switch (ev) {
                        case "started", "resumed" -> "running";
                        case "paused" -> "paused";
                        case "complete" -> "completed";
                        case "paused_on_error" -> "error";
                        default -> "idle";
                    };
                }
            }
            case "interceptor" -> {
                String st = str(d, "state");
                if (st == null || st.equals("inactive")) {
                    interceptor = null;
                } else {
                    JsonObject i = new JsonObject();
                    i.addProperty("state", st);
                    if (d.has("step")) {
                        i.add("step", d.get("step"));
                    }
                    if (d.has("trigger")) {
                        i.add("trigger", d.get("trigger"));
                    }
                    if (d.has("options")) {
                        i.add("options", d.get("options"));
                    }
                    i.add("tool", toolOf(str(d, "trigger")));
                    interceptor = i;
                }
            }
            default -> {
            }
        }
    }

    private static JsonElement toolOf(String trigger) {
        if (trigger == null) {
            return JsonNull.INSTANCE;
        }
        var m = Pattern.compile("T\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(trigger);
        return m.find() ? new com.google.gson.JsonPrimitive("T" + m.group(1)) : JsonNull.INSTANCE;
    }

    private void resetMachine() {
        homed = false;
        alarm = null;
        controller = null;
        interceptor = null;
        streamState = "idle";
        pendingSettings.clear();
    }

    // ------------------------------------------------------------------ views

    public synchronized boolean connected() {
        return connected;
    }

    public synchronized boolean homed() {
        return homed;
    }

    public synchronized String streamState() {
        return streamState;
    }

    public synchronized boolean interceptorActive() {
        return interceptor != null;
    }

    /** Current controller status (a copy), or null. */
    public synchronized JsonObject controller() {
        return controller == null ? null : controller.deepCopy();
    }

    /** WCO from the last status, or null. */
    public synchronized double[] wco() {
        if (controller == null || !controller.has("wco") || !controller.get("wco").isJsonArray()) {
            return null;
        }
        JsonArray a = controller.getAsJsonArray("wco");
        return a.size() == 3 ? new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()} : null;
    }

    /** Waits until a status record newer than {@code afterSeq} arrives; returns it or null on timeout. */
    public synchronized JsonObject awaitStatusAfter(long afterSeq, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (statusSeq <= afterSeq) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                return null;
            }
            wait(left);
        }
        return controller == null ? null : controller.deepCopy();
    }

    public synchronized long statusSeq() {
        return statusSeq;
    }

    /** The {@code connection}, {@code controller}, {@code homed_this_connection}, {@code alarm}, {@code stream},
     *  {@code interceptor} and {@code tool} parts of BridgeStatus. */
    public synchronized void fillStatus(JsonObject s) {
        JsonObject c = new JsonObject();
        c.addProperty("connected", connected);
        c.addProperty("port", connected ? port : null);
        c.addProperty("driver", connected ? driver : null);
        c.addProperty("firmware", connected ? firmware : null);
        c.addProperty("session", connected ? session : null);
        s.add("connection", c);
        s.add("controller", controller == null ? JsonNull.INSTANCE : controller.deepCopy());
        s.addProperty("homed_this_connection", homed);
        s.add("alarm", alarm == null ? JsonNull.INSTANCE : alarm.deepCopy());
        JsonObject st = new JsonObject();
        st.addProperty("state", streamState);
        boolean active = !"idle".equals(streamState);
        st.addProperty("file", active ? streamFile : null);
        st.addProperty("sha256", active ? streamSha : null);
        st.addProperty("line_sent", active ? lineSent : null);
        st.add("line_acked", JsonNull.INSTANCE);
        st.addProperty("lines_total", active ? linesTotal : null);
        s.add("stream", st);
        s.add("interceptor", interceptor == null ? JsonNull.INSTANCE : interceptor.deepCopy());
        s.add("tool", JsonNull.INSTANCE);
    }

    /** {@code GET /settings}: {raw, read_at, settings}. */
    public synchronized JsonObject settingsJson() {
        JsonObject out = new JsonObject();
        out.addProperty("raw", String.join("\n", settings));
        out.addProperty("read_at", settingsReadAt);
        JsonObject map = new JsonObject();
        for (String line : settings) {
            GrblSettingMessage m = new GrblSettingMessage(line);
            if (m.getSetting() == null) {
                continue;
            }
            JsonObject v = new JsonObject();
            try {
                v.addProperty("value", Double.parseDouble(m.getValue()));
            } catch (NumberFormatException e) {
                v.addProperty("value", m.getValue());
            }
            String name = m.getDescription();
            v.addProperty("name", name == null || name.isBlank() ? "$" + m.getSetting() : name);
            v.addProperty("units", m.getUnits() == null ? "" : m.getUnits().toLowerCase(Locale.ROOT).equals("bool") ? "boolean" : m.getUnits());
            map.add("$" + m.getSetting(), v);
        }
        out.add("settings", map);
        return out;
    }

    /** {@code GET /file}: the loaded file, or null. */
    public synchronized JsonObject file() {
        return file == null ? null : file.deepCopy();
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }
}
