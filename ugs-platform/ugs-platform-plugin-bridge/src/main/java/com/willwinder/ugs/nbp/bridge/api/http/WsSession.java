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
package com.willwinder.ugs.nbp.bridge.api.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side of one WebSocket. {@link #sendText}, {@link #ping} and {@link #close} may be called from any thread.
 */
public final class WsSession {
    private final Socket socket;
    private final OutputStream out;
    private final HttpRequest request;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private volatile boolean open = true;
    private volatile boolean closeSent;

    WsSession(Socket socket, OutputStream out, HttpRequest request) {
        this.socket = socket;
        this.out = out;
        this.request = request;
    }

    public HttpRequest request() {
        return request;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    public boolean isOpen() {
        return open;
    }

    /** Sends one text frame; returns false if the socket is closed or the write failed. */
    public boolean sendText(String text) {
        return sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    public boolean ping() {
        return sendFrame(0x9, new byte[0]);
    }

    /** Sends a close frame with the code and closes the socket. */
    public void close(int code, String reason) {
        if (!closeSent && open) {
            byte[] r = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[2 + Math.min(r.length, 120)];
            payload[0] = (byte) (code >> 8);
            payload[1] = (byte) code;
            System.arraycopy(r, 0, payload, 2, payload.length - 2);
            writeFrame(0x8, payload);
        }
        closeSent = true;
        markClosed();
        LiteHttpServer.closeQuietly(socket);
    }

    boolean sendFrame(int opcode, byte[] payload) {
        if (!open || closeSent) {
            return false;
        }
        return writeFrame(opcode, payload);
    }

    private boolean writeFrame(int opcode, byte[] payload) {
        int len = payload.length;
        byte[] head;
        if (len < 126) {
            head = new byte[]{(byte) (0x80 | opcode), (byte) len};
        } else if (len < 65536) {
            head = new byte[]{(byte) (0x80 | opcode), 126, (byte) (len >> 8), (byte) len};
        } else {
            head = new byte[10];
            head[0] = (byte) (0x80 | opcode);
            head[1] = 127;
            for (int i = 0; i < 8; i++) {
                head[9 - i] = (byte) ((long) len >> (8 * i));
            }
        }
        try {
            synchronized (out) {
                out.write(head);
                out.write(payload);
                out.flush();
            }
            return true;
        } catch (IOException e) {
            markClosed();
            LiteHttpServer.closeQuietly(socket);
            return false;
        }
    }

    void markClosed() {
        open = false;
    }
}
