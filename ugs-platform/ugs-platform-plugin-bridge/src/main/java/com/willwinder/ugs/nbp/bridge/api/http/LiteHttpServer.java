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

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A small HTTP/1.1 + WebSocket (RFC 6455) server for the bridge API, using only the JDK.
 * <p>
 * Why not the pendant's Jetty/Jersey: those jars live inside {@code ugs-platform-ugslib} but are not in its public
 * packages, so a NetBeans module can't link against them without an upstream change (and Jersey/HK2 lean on
 * ServiceLoader, which is fragile under NetBeans module class loaders). The API needs a handful of JSON routes,
 * static files and one WebSocket endpoint for a few local clients, so a JDK-only server keeps the module free of
 * extra dependencies.
 * <p>
 * Scope, deliberately small: binds one loopback address; one virtual thread per connection; keep-alive;
 * {@code Content-Length} request bodies only (no chunked uploads); text WebSocket frames, ping/pong and close;
 * no extensions (permessage-deflate is declined by not answering it).
 */
public final class LiteHttpServer implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(LiteHttpServer.class.getName());
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    static final int MAX_HEADER_BYTES = 32 * 1024;
    static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    static final int IDLE_TIMEOUT_MS = 60_000;

    /** Handles plain HTTP requests. */
    public interface Handler {
        HttpResponse handle(HttpRequest request);
    }

    /** Decides on a WebSocket upgrade: deny with an HTTP response, or accept with an endpoint. */
    public interface UpgradeHandler {
        Upgrade upgrade(HttpRequest request);
    }

    /** Callbacks for an accepted WebSocket. Called on the connection's own thread. */
    public interface WsEndpoint {
        void onOpen(WsSession session);

        default void onText(WsSession session, String text) {
        }

        void onClose(WsSession session);
    }

    /** Outcome of an upgrade request: exactly one of {@code deny} / {@code endpoint} is set. */
    public record Upgrade(HttpResponse deny, WsEndpoint endpoint, String subprotocol) {
        public static Upgrade deny(HttpResponse response) {
            return new Upgrade(response, null, null);
        }

        public static Upgrade accept(WsEndpoint endpoint, String subprotocol) {
            return new Upgrade(null, endpoint, subprotocol);
        }
    }

    private final Handler handler;
    private final UpgradeHandler upgradeHandler;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private ServerSocket server;
    private Thread acceptor;
    private volatile boolean closed;

    public LiteHttpServer(Handler handler, UpgradeHandler upgradeHandler) {
        this.handler = handler;
        this.upgradeHandler = upgradeHandler;
    }

    /**
     * Binds 127.0.0.1 on the first free port in {@code [firstPort, lastPort]} (0 = ephemeral) and starts accepting.
     *
     * @return the bound port
     */
    public synchronized int start(int firstPort, int lastPort) throws IOException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        IOException last = null;
        for (int port = firstPort; port <= Math.max(firstPort, lastPort); port++) {
            ServerSocket s = new ServerSocket();
            try {
                s.setReuseAddress(false);
                s.bind(new InetSocketAddress(loopback, port), 50);
                server = s;
                break;
            } catch (BindException e) {
                s.close();
                last = e;
            }
        }
        if (server == null) {
            throw last != null ? last : new IOException("no free port");
        }
        acceptor = new Thread(this::acceptLoop, "ugs-bridge-api-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        return server.getLocalPort();
    }

    public int port() {
        return server == null ? -1 : server.getLocalPort();
    }

    @Override
    public synchronized void close() {
        closed = true;
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "close", e);
        }
        for (Socket s : sockets) {
            closeQuietly(s);
        }
        sockets.clear();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket s = server.accept();
                if (!s.getInetAddress().isLoopbackAddress()) {
                    closeQuietly(s); // can't happen when bound to 127.0.0.1; belt and braces
                    continue;
                }
                sockets.add(s);
                Thread.ofVirtual().name("ugs-bridge-api-conn").start(() -> serve(s));
            } catch (IOException e) {
                if (!closed) {
                    LOGGER.log(Level.WARNING, "accept failed", e);
                }
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(IDLE_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            String remote = socket.getInetAddress().getHostAddress();
            while (!closed) {
                HttpRequest req;
                try {
                    req = readRequest(in, remote);
                } catch (BadRequest e) {
                    write(out, HttpResponse.json(e.status, "{\"error\":\"invalid_request\",\"message\":\"" + e.getMessage() + "\"}"), false);
                    return;
                }
                if (req == null) {
                    return; // client closed
                }
                if (req.isWebSocketUpgrade()) {
                    handleUpgrade(socket, in, out, req);
                    return;
                }
                HttpResponse resp;
                try {
                    resp = handler.handle(req);
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "handler failed for " + req.method() + " " + req.path(), e);
                    resp = HttpResponse.json(500, "{\"error\":\"internal\",\"message\":\"internal error\"}");
                }
                boolean keepAlive = !"close".equalsIgnoreCase(req.header("connection"));
                write(out, resp, keepAlive);
                if (!keepAlive) {
                    return;
                }
            }
        } catch (SocketTimeoutException | SocketException e) {
            // idle keep-alive connection or client went away
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "connection error", e);
        } finally {
            sockets.remove(socket);
        }
    }

    // ------------------------------------------------------------------ HTTP

    static final class BadRequest extends IOException {
        final int status;

        BadRequest(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    static HttpRequest readRequest(InputStream in, String remote) throws IOException {
        String requestLine = readLine(in, true);
        if (requestLine == null) {
            return null;
        }
        while (requestLine.isEmpty()) { // tolerate a stray CRLF between keep-alive requests
            requestLine = readLine(in, true);
            if (requestLine == null) {
                return null;
            }
        }
        String[] parts = requestLine.split(" ");
        if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
            throw new BadRequest(400, "bad request line");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        int total = requestLine.length();
        while (true) {
            String line = readLine(in, false);
            if (line == null) {
                throw new BadRequest(400, "truncated headers");
            }
            if (line.isEmpty()) {
                break;
            }
            total += line.length();
            if (total > MAX_HEADER_BYTES) {
                throw new BadRequest(431, "headers too large");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new BadRequest(400, "bad header");
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            headers.merge(name, value, (a, b) -> a + ", " + b);
        }
        String target = parts[1];
        String path = target;
        String rawQuery = "";
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            rawQuery = target.substring(q + 1);
        }
        byte[] body = new byte[0];
        if (headers.containsKey("transfer-encoding")) {
            throw new BadRequest(411, "chunked bodies are not supported; send Content-Length");
        }
        String cl = headers.get("content-length");
        if (cl != null) {
            int len;
            try {
                len = Integer.parseInt(cl.trim());
            } catch (NumberFormatException e) {
                throw new BadRequest(400, "bad content-length");
            }
            if (len < 0 || len > MAX_BODY_BYTES) {
                throw new BadRequest(413, "body too large");
            }
            body = in.readNBytes(len);
            if (body.length != len) {
                throw new BadRequest(400, "truncated body");
            }
        }
        return new HttpRequest(parts[0].toUpperCase(Locale.ROOT), decode(path), parseQuery(rawQuery), headers, body, remote);
    }

    /** Reads one CRLF (or LF) terminated ISO-8859-1 line; null at EOF before any byte. */
    private static String readLine(InputStream in, boolean eofOk) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) {
                return sb.isEmpty() && eofOk ? null : (sb.isEmpty() ? null : sb.toString());
            }
            if (b == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') {
                    sb.setLength(n - 1);
                }
                return sb.toString();
            }
            sb.append((char) b);
            if (sb.length() > MAX_HEADER_BYTES) {
                throw new BadRequest(431, "line too long");
            }
        }
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return m;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            m.putIfAbsent(decode(k), decode(v));
        }
        return m;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    static void write(OutputStream out, HttpResponse resp, boolean keepAlive) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(resp.status()).append(' ').append(HttpResponse.reason(resp.status())).append("\r\n");
        head.append("Content-Type: ").append(resp.contentType()).append("\r\n");
        head.append("Content-Length: ").append(resp.body().length).append("\r\n");
        head.append("Cache-Control: no-store\r\n");
        head.append("X-Content-Type-Options: nosniff\r\n");
        for (Map.Entry<String, String> h : resp.headers().entrySet()) {
            head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        head.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n\r\n");
        synchronized (out) {
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(resp.body());
            out.flush();
        }
    }

    // ------------------------------------------------------------------ WebSocket

    private void handleUpgrade(Socket socket, InputStream in, OutputStream out, HttpRequest req) throws IOException {
        String key = req.header("sec-websocket-key");
        if (key == null || !"13".equals(req.header("sec-websocket-version"))) {
            write(out, HttpResponse.json(400, "{\"error\":\"invalid_request\",\"message\":\"bad websocket handshake\"}"), false);
            return;
        }
        Upgrade up;
        try {
            up = upgradeHandler.upgrade(req);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "upgrade handler failed", e);
            up = Upgrade.deny(HttpResponse.json(500, "{\"error\":\"internal\",\"message\":\"internal error\"}"));
        }
        if (up.deny() != null) {
            write(out, up.deny(), false);
            return;
        }
        StringBuilder head = new StringBuilder("HTTP/1.1 101 Switching Protocols\r\n")
                .append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
                .append("Sec-WebSocket-Accept: ").append(acceptKey(key)).append("\r\n");
        if (up.subprotocol() != null) {
            head.append("Sec-WebSocket-Protocol: ").append(up.subprotocol()).append("\r\n");
        }
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        socket.setSoTimeout(0); // the server pings; reads may block indefinitely
        WsSession session = new WsSession(socket, out, req);
        WsEndpoint ep = up.endpoint();
        try {
            ep.onOpen(session);
            readFrames(in, session, ep);
        } finally {
            session.markClosed();
            ep.onClose(session);
        }
    }

    static String acceptKey(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return Base64.getEncoder().encodeToString(sha1.digest((key.trim() + WS_GUID).getBytes(StandardCharsets.ISO_8859_1)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void readFrames(InputStream in, WsSession session, WsEndpoint ep) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        int messageOpcode = -1;
        while (session.isOpen()) {
            int b0 = in.read();
            int b1 = in.read();
            if (b0 < 0 || b1 < 0) {
                return;
            }
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0f;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7f;
            if (len == 126) {
                len = ((long) readByte(in) << 8) | readByte(in);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | readByte(in);
                }
            }
            if (len > MAX_BODY_BYTES || !masked) {
                session.close(1009, "frame too large or unmasked");
                return;
            }
            byte[] mask = in.readNBytes(4);
            byte[] payload = in.readNBytes((int) len);
            if (mask.length != 4 || payload.length != len) {
                return;
            }
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i & 3];
            }
            switch (opcode) {
                case 0x8 -> { // close: echo the code and finish
                    int code = payload.length >= 2 ? ((payload[0] & 0xff) << 8) | (payload[1] & 0xff) : 1000;
                    session.close(code == 1005 || code == 1006 ? 1000 : code, "");
                    return;
                }
                case 0x9 -> session.sendFrame(0xA, payload);
                case 0xA -> {
                    // pong
                }
                case 0x0, 0x1, 0x2 -> {
                    if (opcode != 0) {
                        messageOpcode = opcode;
                        message.reset();
                    }
                    message.write(payload);
                    if (message.size() > MAX_BODY_BYTES) {
                        session.close(1009, "message too large");
                        return;
                    }
                    if (fin) {
                        if (messageOpcode == 0x1) {
                            ep.onText(session, message.toString(StandardCharsets.UTF_8));
                        }
                        message.reset();
                    }
                }
                default -> {
                    session.close(1002, "bad opcode");
                    return;
                }
            }
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new IOException("eof");
        }
        return b;
    }

    static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException e) {
            // ignore
        }
    }
}
