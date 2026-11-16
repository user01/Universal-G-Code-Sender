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

import com.willwinder.ugs.nbp.bridge.capture.Capture;
import com.willwinder.ugs.nbp.bridge.capture.CommandOrigins;
import com.willwinder.universalgcodesender.IController;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.MessageType;
import com.willwinder.universalgcodesender.listeners.UGSEventListener;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.UGSEvent;
import com.willwinder.universalgcodesender.model.events.CommandEvent;
import com.willwinder.universalgcodesender.model.events.CommandEventType;
import com.willwinder.universalgcodesender.model.events.ControllerStateEvent;
import com.willwinder.universalgcodesender.types.GcodeCommand;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link MachineLink} over UGS's {@link BackendAPI}. Lines go through {@code sendGcodeCommand(GcodeCommand)} with
 * {@link GcodeCommand#setOrigin} set (patch P1: the console shows {@code [claude] >>> ...}) and are registered with
 * {@link CommandOrigins} so the capture logs them with the bridge's {@code cmd_id} and {@code src}.
 */
public final class BackendMachineLink implements MachineLink, UGSEventListener {
    private static final Pattern ERROR = Pattern.compile("^error:(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALARM = Pattern.compile("^ALARM:(\\d+)", Pattern.CASE_INSENSITIVE);

    private final BackendAPI backend;
    private final CommandOrigins origins;
    private final Capture capture;
    private final Map<Integer, CompletableFuture<LineResult>> pending = new ConcurrentHashMap<>();

    public BackendMachineLink(BackendAPI backend, CommandOrigins origins, Capture capture) {
        this.backend = backend;
        this.origins = origins;
        this.capture = capture;
        backend.addUGSEventListener(this);
    }

    public void dispose() {
        backend.removeUGSEventListener(this);
        abandonPending("bridge stopped");
    }

    @Override
    public boolean isConnected() {
        return backend.isConnected();
    }

    @Override
    public boolean isStreaming() {
        IController c = backend.getController();
        return c != null && Boolean.TRUE.equals(c.isStreaming());
    }

    @Override
    public CompletableFuture<LineResult> send(String line, String cmdId, String src, String tag) {
        CompletableFuture<LineResult> f = new CompletableFuture<>();
        try {
            IController c = backend.getController();
            if (c == null || !backend.isConnected()) {
                throw new IllegalStateException("not connected");
            }
            GcodeCommand cmd = c.createCommand(line);
            cmd.setOrigin(src);
            origins.register(cmd, cmdId, src, tag);
            pending.put(cmd.getId(), f);
            try {
                backend.sendGcodeCommand(cmd);
            } catch (Exception e) {
                pending.remove(cmd.getId());
                throw e;
            }
        } catch (Exception e) {
            f.completeExceptionally(e);
        }
        return f;
    }

    @Override
    public void abandonPending(String reason) {
        for (Integer id : List.copyOf(pending.keySet())) {
            CompletableFuture<LineResult> f = pending.remove(id);
            if (f != null) {
                f.complete(new LineResult("error", List.of("(no reply: " + reason + ")"), null, null));
            }
        }
    }

    @Override
    public void UGSEvent(UGSEvent evt) {
        if (evt instanceof CommandEvent e && e.getCommand() != null) {
            GcodeCommand cmd = e.getCommand();
            if (e.getCommandEventType() == CommandEventType.COMMAND_COMPLETE) {
                CompletableFuture<LineResult> f = pending.remove(cmd.getId());
                if (f != null) {
                    f.complete(result(cmd));
                }
            } else if (e.getCommandEventType() == CommandEventType.COMMAND_SKIPPED) {
                CompletableFuture<LineResult> f = pending.remove(cmd.getId());
                if (f != null) {
                    f.complete(new LineResult("skipped", responseLines(cmd.getResponse()), null, null));
                }
            }
        } else if (evt instanceof ControllerStateEvent e && e.getState() == ControllerState.DISCONNECTED) {
            abandonPending("disconnected");
        }
    }

    static LineResult result(GcodeCommand cmd) {
        List<String> lines = responseLines(cmd.getResponse());
        Integer error = null;
        Integer alarm = null;
        for (String l : lines) {
            Matcher m = ERROR.matcher(l);
            if (error == null && m.find()) {
                error = Integer.parseInt(m.group(1));
            }
            m = ALARM.matcher(l);
            if (alarm == null && m.find()) {
                alarm = Integer.parseInt(m.group(1));
            }
        }
        String state = cmd.isOk() ? "ok" : (alarm != null && error == null ? "alarm" : "error");
        return new LineResult(state, lines, error, alarm);
    }

    static List<String> responseLines(String response) {
        List<String> out = new ArrayList<>();
        if (response != null) {
            for (String l : response.split("\n")) {
                String s = l.strip();
                if (!s.isEmpty() && !(s.startsWith("<") && s.endsWith(">"))) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    @Override
    public String realtime(String op, String src) throws Exception {
        String hex = MachineLink.realtimeHex(op);
        IController c = backend.getController();
        if (c == null || !backend.isConnected()) {
            throw new IllegalStateException("not connected");
        }
        byte b = (byte) Integer.parseInt(hex.substring(2), 16);
        // The byte is logged as tx by UGS's real-time hook (P3); claim it for Claude before it goes out.
        // '?' polls are implied by status records.
        if (capture != null && !"status".equals(op)) {
            capture.attributeRealtime(b, src, null);
        }
        switch (op) {
            case "hold" -> {
                if (Boolean.TRUE.equals(c.isStreaming()) && !Boolean.TRUE.equals(c.isPaused())) {
                    c.pauseStreaming(); // sends '!' and pauses UGS's stream
                } else {
                    c.getCommunicator().sendByteImmediately(b);
                }
            }
            case "resume" -> {
                if (Boolean.TRUE.equals(c.isPaused())) {
                    c.resumeStreaming();
                } else {
                    c.getCommunicator().sendByteImmediately(b);
                }
            }
            case "jog_cancel" -> c.cancelJog();
            case "reset" -> backend.issueSoftReset();
            case "status" -> c.requestStatusReport();
            default -> throw new IllegalArgumentException(op);
        }
        return hex;
    }

    @Override
    public File loadedFile() {
        return backend.getGcodeFile();
    }

    @Override
    public void loadFile(File file) throws Exception {
        backend.setGcodeFile(file);
    }

    @Override
    public void startStream(String src) throws Exception {
        if (capture != null) {
            capture.attributeNextStream(src);
        }
        backend.send();
    }

    @Override
    public void consoleMessage(String text) {
        backend.dispatchMessage(MessageType.INFO, text.endsWith("\n") ? text : text + "\n");
    }
}
