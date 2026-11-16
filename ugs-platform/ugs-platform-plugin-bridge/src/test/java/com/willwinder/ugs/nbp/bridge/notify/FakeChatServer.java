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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Loopback stand-in for the Telegram Bot API and Slack webhooks (127.0.0.1, ephemeral port).
 * Records every request; answers with queued responses, else a success response for the path.
 */
final class FakeChatServer implements AutoCloseable {
    record Request(String method, String path, String contentType, String body) {
    }

    record Response(int status, String body) {
    }

    private final HttpServer server;
    final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Deque<Response> queued = new ArrayDeque<>();

    FakeChatServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    synchronized void enqueue(int status, String body) {
        queued.addLast(new Response(status, body));
    }

    private void handle(HttpExchange ex) throws IOException {
        String body;
        try (InputStream in = ex.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String path = ex.getRequestURI().getRawPath();
        requests.add(new Request(ex.getRequestMethod(), path, ex.getRequestHeaders().getFirst("Content-Type"), body));
        Response r;
        synchronized (this) {
            r = queued.pollFirst();
        }
        if (r == null) {
            r = path.contains("/sendMessage")
                    ? new Response(200, "{\"ok\":true,\"result\":{\"message_id\":" + requests.size() + "}}")
                    : new Response(200, "ok");
        }
        byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", r.body().startsWith("{") ? "application/json" : "text/plain");
        ex.sendResponseHeaders(r.status(), out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
