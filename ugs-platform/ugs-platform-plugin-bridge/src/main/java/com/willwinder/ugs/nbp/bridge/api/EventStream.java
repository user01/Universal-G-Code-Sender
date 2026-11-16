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

import com.google.gson.JsonObject;
import com.willwinder.ugs.nbp.bridge.api.http.LiteHttpServer;
import com.willwinder.ugs.nbp.bridge.api.http.WsSession;
import com.willwinder.ugs.nbp.bridge.log.LogRecord;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * {@code WS /events}: the backlog after {@code since} from the session log's in-memory ring, then live records, one
 * JSON record per text frame, with a ping every {@value #PING_SECONDS} s. A client that falls more than
 * {@value #MAX_BACKLOG} records behind is closed with 1013 (try again) and can resume with {@code since}.
 */
final class EventStream implements LiteHttpServer.WsEndpoint {
    static final int PING_SECONDS = 15;
    static final int MAX_BACKLOG = 100_000;
    private static final LogRecord CLOSE = new LogRecord(-1, "", "", null, "");

    private final SessionLog log;
    private final String client;
    private final long since;
    private final Set<String> kinds;
    private final Set<EventStream> registry;
    private final BlockingQueue<LogRecord> queue = new LinkedBlockingQueue<>(MAX_BACKLOG);
    private final Consumer<LogRecord> listener = this::offer;
    private volatile WsSession session;
    private volatile boolean overflow;

    EventStream(SessionLog log, String client, long since, Set<String> kinds, Set<EventStream> registry) {
        this.log = log;
        this.client = client;
        this.since = since;
        this.kinds = kinds;
        this.registry = registry;
    }

    String client() {
        return client;
    }

    private void offer(LogRecord r) {
        if (kinds != null && !kinds.contains(r.kind())) {
            return;
        }
        if (!queue.offer(r)) {
            overflow = true;
        }
    }

    @Override
    public void onOpen(WsSession s) {
        session = s;
        log.addListener(listener); // first, so nothing falls between the backlog and the live records
        registry.add(this);
        logClient("connect");
        Thread.ofVirtual().name("ugs-bridge-events-" + client).start(this::pump);
    }

    private void pump() {
        WsSession s = session;
        long sent = since;
        for (LogRecord r : log.since(since, kinds, Integer.MAX_VALUE)) {
            if (!s.sendText(r.json())) {
                return;
            }
            sent = r.seq();
        }
        while (s.isOpen()) {
            LogRecord r;
            try {
                r = queue.poll(PING_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (overflow) {
                s.close(1013, "client too slow; reconnect with since=" + sent);
                return;
            }
            if (r == null) {
                if (!s.ping()) {
                    return;
                }
                continue;
            }
            if (r == CLOSE) {
                return;
            }
            if (r.seq() > sent) {
                if (!s.sendText(r.json())) {
                    return;
                }
                sent = r.seq();
            }
        }
    }

    /** Closes the socket with {@code code} (4423 is reserved; locking no longer closes streams). */
    void close(int code, String reason) {
        WsSession s = session;
        if (s != null) {
            s.close(code, reason);
        }
        queue.offer(CLOSE);
    }

    @Override
    public void onClose(WsSession s) {
        log.removeListener(listener);
        registry.remove(this);
        queue.offer(CLOSE);
        logClient("disconnect");
    }

    private void logClient(String action) {
        JsonObject d = new JsonObject();
        d.addProperty("client", client);
        d.addProperty("action", action);
        d.addProperty("remote", session == null ? "" : session.request().remote());
        d.addProperty("endpoint", "events");
        log.append(LogRecord.Kind.CLIENT, LogRecord.Src.BRIDGE, d);
    }

    static Set<EventStream> newRegistry() {
        return ConcurrentHashMap.newKeySet();
    }
}
