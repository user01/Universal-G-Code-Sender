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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A stand-in for UGS + controller: answers lines like GRBL and writes the records the capture would write
 * ({@code tx}, {@code rx}, {@code command sent/ok}, {@code status}), so the state tracker sees a realistic log.
 * Lines listed in {@link #hold} are left unanswered until {@link #release}.
 */
final class FakeLink implements MachineLink {
    final SessionLog log;
    final List<String> sent = Collections.synchronizedList(new ArrayList<>());
    final List<String> realtime = Collections.synchronizedList(new ArrayList<>());
    final List<String> hold = Collections.synchronizedList(new ArrayList<>());
    private final List<CompletableFuture<LineResult>> held = Collections.synchronizedList(new ArrayList<>());
    volatile boolean connected = true;
    volatile boolean streaming;
    volatile File file;
    volatile String streamedBy;
    volatile String state = "Idle";

    FakeLink(SessionLog log) {
        this.log = log;
    }

    /** What UGS + GRBL would log at connect. */
    void connect() {
        JsonObject d = new JsonObject();
        d.addProperty("event", "connect");
        d.addProperty("port", "127.0.0.1:2323");
        d.addProperty("driver", "tcp");
        d.addProperty("firmware", "GRBL");
        d.addProperty("ugs_version", "test");
        d.addProperty("bridge_version", "test");
        log.openSession("tcp_127.0.0.1_2323");
        log.append(LogRecord.Kind.SESSION, LogRecord.Src.OPERATOR, d);
        rx("Grbl 1.1f ['$' for help]", null, LogRecord.Src.CONTROLLER);
        status();
    }

    void status() {
        JsonObject st = new JsonObject();
        st.addProperty("state", state);
        st.add("substate", null);
        st.add("mpos", arr(-10, -20, -5));
        st.add("wpos", arr(0, 0, 0));
        st.add("wco", arr(-10, -20, -5));
        JsonObject pins = new JsonObject();
        pins.addProperty("raw", "");
        pins.addProperty("probe", false);
        st.add("pins", pins);
        st.addProperty("raw", "<" + state + "|MPos:-10.000,-20.000,-5.000|FS:0,0|WCO:-10.000,-20.000,-5.000>");
        log.append(LogRecord.Kind.STATUS, LogRecord.Src.CONTROLLER, st);
    }

    private static JsonArray arr(double... v) {
        JsonArray a = new JsonArray();
        for (double x : v) {
            a.add(x);
        }
        return a;
    }

    private void rx(String line, String cmdId, String src) {
        JsonObject d = new JsonObject();
        d.addProperty("line", line);
        if (cmdId != null) {
            d.addProperty("cmd_id", cmdId);
        }
        log.append(LogRecord.Kind.RX, src, d);
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public boolean isStreaming() {
        return streaming;
    }

    @Override
    public CompletableFuture<LineResult> send(String line, String cmdId, String src, String tag) {
        if (!connected) {
            return CompletableFuture.failedFuture(new IllegalStateException("not connected"));
        }
        sent.add(line);
        JsonObject tx = new JsonObject();
        tx.addProperty("line", line);
        tx.addProperty("cmd_id", cmdId);
        log.append(LogRecord.Kind.TX, src, tx);
        JsonObject c = new JsonObject();
        c.addProperty("id", cmdId);
        c.addProperty("origin", src);
        c.addProperty("line", line);
        c.addProperty("state", "sent");
        log.append(LogRecord.Kind.COMMAND, src, c);
        CompletableFuture<LineResult> f = new CompletableFuture<>();
        if (hold.contains(line)) {
            held.add(f);
            return f;
        }
        f.complete(answer(line, cmdId, src));
        return f;
    }

    /** Answers every held line with ok. */
    void release() {
        List<CompletableFuture<LineResult>> copy;
        synchronized (held) {
            copy = new ArrayList<>(held);
            held.clear();
        }
        copy.forEach(f -> f.complete(new LineResult("ok", List.of("ok"), null, null)));
    }

    private LineResult answer(String line, String cmdId, String src) {
        List<String> resp = new ArrayList<>();
        String norm = Classifier.normalize(line);
        if (norm.equals("$$")) {
            resp.addAll(List.of("$0=10", "$13=0", "$130=445.000", "$131=456.000", "$132=95.000"));
        } else if (norm.equals("$#")) {
            resp.addAll(List.of("[G54:-100.000,-200.000,-30.000]", "[G55:0.000,0.000,0.000]", "[PRB:0.000,0.000,0.000:0]"));
        } else if (norm.equals("$G")) {
            resp.add("[GC:G0 G54 G17 G21 G90 G94 M5 M9 T0 F0 S0]");
        }
        String last = norm.startsWith("G999") ? "error:20" : norm.startsWith("G38.2Z-99") ? "ALARM:5" : "ok";
        resp.add(last);
        for (String r : resp) {
            rx(r, cmdId, src);
        }
        String state = last.equals("ok") ? "ok" : last.startsWith("error") ? "error" : "alarm";
        JsonObject c = new JsonObject();
        c.addProperty("id", cmdId);
        c.addProperty("origin", src);
        c.addProperty("line", line);
        c.addProperty("state", state);
        JsonArray ra = new JsonArray();
        resp.forEach(ra::add);
        c.add("response", ra);
        log.append(LogRecord.Kind.COMMAND, src, c);
        if (last.startsWith("ALARM")) {
            JsonObject ev = new JsonObject();
            ev.addProperty("type", "alarm");
            ev.addProperty("code", Integer.parseInt(last.substring(6)));
            log.append(LogRecord.Kind.EVENT, LogRecord.Src.CONTROLLER, ev);
        }
        Integer err = last.startsWith("error:") ? Integer.parseInt(last.substring(6)) : null;
        Integer alarm = last.startsWith("ALARM:") ? Integer.parseInt(last.substring(6)) : null;
        return new LineResult(state, resp, err, alarm);
    }

    @Override
    public String realtime(String op, String src) {
        if (!connected) {
            throw new IllegalStateException("not connected");
        }
        realtime.add(op);
        if (op.equals("status")) {
            status();
        } else if (op.equals("reset")) {
            rx("Grbl 1.1f ['$' for help]", null, LogRecord.Src.CONTROLLER);
        }
        return MachineLink.realtimeHex(op);
    }

    @Override
    public File loadedFile() {
        return file;
    }

    @Override
    public void loadFile(File f) {
        file = f;
    }

    @Override
    public void startStream(String src) {
        streamedBy = src;
        streaming = true;
    }

    @Override
    public void consoleMessage(String text) {
    }
}
