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
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The two HTTP sinks. Calls are blocking and run on the notifier's sender thread, never on a UGS thread.
 * Each send has a request timeout and is retried once on a network error, HTTP 429 or 5xx.
 * Base URLs are injectable so tests can point them at a loopback fake server.
 */
public final class Sinks {
    public static final URI TELEGRAM_API = URI.create("https://api.telegram.org");
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    static final long RETRY_DELAY_MILLIS = 1000;
    static final long MAX_RETRY_AFTER_MILLIS = 5000;

    public static final String OK = "ok";
    public static final String FAILED = "failed";
    public static final String DISABLED = "disabled";
    public static final String RATE_LIMITED = "rate_limited";

    private Sinks() {
    }

    /** Result of one sink: {@code ok|failed} plus a masked detail for failures. */
    public record Result(String status, String detail) {
        static Result ok() {
            return new Result(OK, null);
        }

        static Result failed(String detail) {
            return new Result(FAILED, detail);
        }
    }

    public interface Sink {
        String name();

        Result send(String text);
    }

    public static HttpClient newClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Telegram Bot API {@code POST <base>/bot<token>/sendMessage} with {@code {chat_id, text}}. */
    public static final class Telegram implements Sink {
        private final HttpClient client;
        private final URI base;
        private final String token;
        private final String chatId;
        private final long retryDelayMillis;

        public Telegram(HttpClient client, URI base, String token, String chatId) {
            this(client, base, token, chatId, RETRY_DELAY_MILLIS);
        }

        Telegram(HttpClient client, URI base, String token, String chatId, long retryDelayMillis) {
            this.client = client;
            this.base = base;
            this.token = token;
            this.chatId = chatId;
            this.retryDelayMillis = retryDelayMillis;
        }

        @Override
        public String name() {
            return "telegram";
        }

        @Override
        public Result send(String text) {
            JsonObject body = new JsonObject();
            // numeric ids are sent as numbers, @channel names as strings
            if (chatId.matches("-?\\d+")) {
                body.addProperty("chat_id", Long.parseLong(chatId));
            } else {
                body.addProperty("chat_id", chatId);
            }
            body.addProperty("text", text);
            body.addProperty("disable_web_page_preview", true);
            URI uri = URI.create(stripSlash(base.toString()) + "/bot" + token + "/sendMessage");
            return post(client, uri, body.toString(), retryDelayMillis, Telegram::accepted, token);
        }

        private static String accepted(HttpResponse<String> r) {
            if (r.statusCode() == 200) {
                try {
                    JsonElement el = JsonParser.parseString(r.body());
                    if (el.isJsonObject() && el.getAsJsonObject().has("ok") && el.getAsJsonObject().get("ok").getAsBoolean()) {
                        return null;
                    }
                } catch (RuntimeException e) {
                    return "HTTP 200 with unreadable body";
                }
                return "HTTP 200 but ok=false";
            }
            return "HTTP " + r.statusCode() + describe(r.body());
        }

        private static String describe(String body) {
            try {
                JsonObject o = JsonParser.parseString(body).getAsJsonObject();
                if (o.has("description")) {
                    String d = o.get("description").getAsString();
                    return ": " + (d.length() > 120 ? d.substring(0, 120) : d);
                }
            } catch (RuntimeException e) {
                // not JSON
            }
            return "";
        }
    }

    /** Slack incoming webhook: {@code POST <webhook>} with {@code {text}}; Slack answers {@code ok}. */
    public static final class Slack implements Sink {
        private final HttpClient client;
        private final URI webhook;
        private final String secret;
        private final long retryDelayMillis;

        /**
         * @param baseOverride if not null, scheme/host/port of the webhook are replaced by this base (tests);
         *                     the path is kept
         */
        public Slack(HttpClient client, String webhookUrl, URI baseOverride) {
            this(client, webhookUrl, baseOverride, RETRY_DELAY_MILLIS);
        }

        Slack(HttpClient client, String webhookUrl, URI baseOverride, long retryDelayMillis) {
            this.client = client;
            this.secret = webhookUrl;
            URI u = URI.create(webhookUrl);
            this.webhook = baseOverride == null ? u
                    : URI.create(stripSlash(baseOverride.toString()) + u.getRawPath()
                    + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery()));
            this.retryDelayMillis = retryDelayMillis;
        }

        @Override
        public String name() {
            return "slack";
        }

        @Override
        public Result send(String text) {
            JsonObject body = new JsonObject();
            body.addProperty("text", text);
            return post(client, webhook, body.toString(), retryDelayMillis, Slack::accepted, secret, webhook.getRawPath());
        }

        private static String accepted(HttpResponse<String> r) {
            if (r.statusCode() == 200) {
                return null;
            }
            String b = r.body() == null ? "" : r.body().strip();
            // Slack answers with short codes like invalid_token, no_service, channel_not_found
            return "HTTP " + r.statusCode() + (b.isEmpty() || b.length() > 60 ? "" : ": " + b);
        }
    }

    interface Acceptor {
        /** null if the response means "delivered", else a short reason. */
        String check(HttpResponse<String> r);
    }

    static Result post(HttpClient client, URI uri, String json, long retryDelayMillis, Acceptor acceptor, String... secrets) {
        String last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            long retryAfter = retryDelayMillis;
            try {
                HttpRequest req = HttpRequest.newBuilder(uri)
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json; charset=utf-8")
                        .header("User-Agent", "ugs-bridge-notify/1")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                String problem = acceptor.check(r);
                if (problem == null) {
                    return Result.ok();
                }
                last = problem;
                int code = r.statusCode();
                if (code != 429 && code < 500) {
                    break; // a 4xx won't get better on retry
                }
                if (code == 429) {
                    retryAfter = retryAfterMillis(r, retryDelayMillis);
                }
            } catch (IOException e) {
                last = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.failed("interrupted");
            } catch (RuntimeException e) {
                last = e.getClass().getSimpleName();
                break;
            }
            if (attempt == 1) {
                try {
                    Thread.sleep(retryAfter);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Result.failed("interrupted");
                }
            }
        }
        return Result.failed(Masking.scrub(last, secrets));
    }

    private static long retryAfterMillis(HttpResponse<String> r, long dflt) {
        long ms = dflt;
        try {
            String h = r.headers().firstValue("Retry-After").orElse(null);
            if (h != null) {
                ms = Long.parseLong(h.trim()) * 1000;
            } else {
                JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                if (o.has("parameters") && o.getAsJsonObject("parameters").has("retry_after")) {
                    ms = o.getAsJsonObject("parameters").get("retry_after").getAsLong() * 1000;
                }
            }
        } catch (RuntimeException e) {
            // keep default
        }
        return Math.max(0, Math.min(ms, MAX_RETRY_AFTER_MILLIS));
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
