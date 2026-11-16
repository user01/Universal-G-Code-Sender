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
import com.google.gson.JsonParser;
import com.willwinder.ugs.nbp.bridge.BridgeLock;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;
import com.willwinder.ugs.nbp.bridge.notify.Notifier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * The real API (ApiHost: server, authority, tracker, command queue) on an ephemeral loopback port, with a temp
 * bridge home, a {@link FakeLink} instead of UGS, scripted operator answers and a settable clock.
 */
final class ApiFixture implements AutoCloseable {
    final Path home;
    final SessionLog log;
    final BridgeLock lock = new BridgeLock();
    final TestClock clock = new TestClock();
    /** Wall clock for the status-report age ({@code controller_report}). */
    final java.util.concurrent.atomic.AtomicLong millis = new java.util.concurrent.atomic.AtomicLong(1_800_000_000_000L);
    final Authority authority;
    final FakeLink link;
    final Prompts prompts = new Prompts();
    final List<String[]> notified = Collections.synchronizedList(new ArrayList<>());
    final ApiHost host;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final String token;

    ApiFixture(Path home) throws Exception {
        this(home, true);
    }

    ApiFixture(Path home, boolean withNotifier) throws Exception {
        this.home = home;
        log = new SessionLog(home);
        log.start();
        authority = new Authority(lock, log, clock);
        authority.logStart();
        link = new FakeLink(log);
        Notifier n = withNotifier ? (t, text, level) -> notified.add(new String[]{t, text, level}) : BridgeApi.NO_NOTIFIER;
        host = new ApiHost(home, log, authority, link, prompts, () -> n, millis::get);
        host.start(0, 0);
        token = Files.readString(home.resolve("token")).strip();
        link.connect();
    }

    String base() {
        return "http://127.0.0.1:" + host.port() + BridgeApi.BASE;
    }

    String viewerToken() {
        return host.api().viewerToken();
    }

    HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).GET();
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path) throws Exception {
        return get(path, token);
    }

    HttpResponse<String> post(String path, String json, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path))
                .POST(HttpRequest.BodyPublishers.ofString(json)).header("Content-Type", "application/json");
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String json) throws Exception {
        return post(path, json, token);
    }

    static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    /** Polls {@code GET /command/{id}} until its state is terminal (or {@code awaiting_operator} if asked). */
    JsonObject awaitCommand(String id, String... states) throws Exception {
        List<String> want = states.length == 0 ? List.of("done", "failed", "dropped") : List.of(states);
        long deadline = System.currentTimeMillis() + 5000;
        JsonObject r;
        do {
            r = json(get("/command/" + id));
            if (want.contains(r.get("state").getAsString())) {
                return r;
            }
            Thread.sleep(10);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("command " + id + " stuck in " + r);
    }

    static void await(BooleanSupplier cond, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    void unlock(Level level) {
        lock.unlock();
        if (level != Level.QUERY) {
            authority.setLevel(level, "operator");
        }
    }

    @Override
    public void close() {
        host.close();
        authority.dispose();
        log.close();
    }

    /** Operator answers: approve, decline, or leave open until answered/cancelled. */
    static final class Prompts implements OperatorPrompts {
        volatile Boolean answer = true; // null = leave open
        final List<String> asked = Collections.synchronizedList(new ArrayList<>());
        final List<CompletableFuture<Boolean>> open = Collections.synchronizedList(new ArrayList<>());
        volatile int cancelled;

        @Override
        public Prompt confirm(String title, String message, String approve) {
            asked.add(title + "\n" + message);
            CompletableFuture<Boolean> f = new CompletableFuture<>();
            Boolean a = answer;
            if (a != null) {
                f.complete(a);
            } else {
                open.add(f);
            }
            return new Prompt() {
                @Override
                public CompletableFuture<Boolean> result() {
                    return f;
                }

                @Override
                public void cancel() {
                    cancelled++;
                    f.complete(false);
                }
            };
        }

        void answerOpen(boolean yes) {
            List<CompletableFuture<Boolean>> copy;
            synchronized (open) {
                copy = new ArrayList<>(open);
                open.clear();
            }
            copy.forEach(f -> f.complete(yes));
        }
    }

    static final class TestClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-15T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
