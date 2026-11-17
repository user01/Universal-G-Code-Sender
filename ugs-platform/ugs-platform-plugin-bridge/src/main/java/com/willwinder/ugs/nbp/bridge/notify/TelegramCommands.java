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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.willwinder.ugs.nbp.bridge.log.LogRecord;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Two-way Telegram (PLAN D26): the operator can ask the bot for the machine's state and pause a job from the
 * configured chat. A fixed vocabulary, no language model: {@code status}, {@code eta}, {@code tools}, {@code log},
 * {@code pause}, {@code help} (case-insensitive, with or without a leading {@code /}).
 *
 * <ul>
 *   <li>Outbound only: long-polls {@code getUpdates} (no open port, no webhook).</li>
 *   <li>Only private messages from the configured {@code chat_id} are answered; everything else is ignored and
 *       logged once per sender (numeric id only).</li>
 *   <li>Messages that were waiting when the bridge started are skipped, so an old {@code pause} never fires late.</li>
 *   <li>{@code pause} sends a feed hold, only while a job is running. It is the operator's safety action, so it works
 *       while Claude is locked out. Start, resume, unlock, homing, jogging, spindle and settings are refused:
 *       "Do that at the machine."</li>
 *   <li>The bot token is never logged; errors are scrubbed.</li>
 * </ul>
 */
public final class TelegramCommands implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(TelegramCommands.class.getName());
    static final int POLL_TIMEOUT_S = 25;
    static final long MAX_BACKOFF_MS = 60_000;
    static final String AT_THE_MACHINE = "Do that at the machine.";
    static final Set<String> REFUSED = Set.of("start", "run", "resume", "continue", "go", "cycle", "unlock", "lock",
            "home", "jog", "move", "spindle", "router", "m3", "m4", "m5", "reset", "stop", "cancel", "set", "zero",
            "probe", "send");

    /** The Bot API calls (a loopback fake in tests). */
    public interface Transport {
        /** {@code getUpdates}: the response body. */
        String getUpdates(String token, long offset, int timeoutS) throws IOException, InterruptedException;

        void sendMessage(String token, String chatId, String text) throws IOException, InterruptedException;
    }

    /** What the commands read and do. Implemented over the API's state (bridge.api.TelegramAccess). */
    public interface Machine {
        String status();

        String eta();

        String tools();

        /** The last {@code n} notable events, one per line. */
        String log(int n);

        /** True while a file is streaming (running, not paused). */
        boolean jobRunning();

        /** Sends a feed hold; returns a short result for the reply. */
        String pause() throws Exception;
    }

    private final Supplier<NotifyConfig> config;
    private final Transport transport;
    private final SessionLog log;
    private volatile Machine machine;
    private final Set<String> ignoredSenders = new HashSet<>();
    private volatile boolean closed;
    private Thread thread;
    private long offset;
    private boolean skippedBacklog;
    private long backoffMs;

    public TelegramCommands(Supplier<NotifyConfig> config, Transport transport, SessionLog log) {
        this.config = config;
        this.transport = transport;
        this.log = log;
    }

    /** The machine side; until it is set the bot answers that the bridge API isn't running. */
    public void setMachine(Machine m) {
        this.machine = m;
    }

    public synchronized void start() {
        if (thread != null) {
            return;
        }
        thread = new Thread(this::loop, "ugs-bridge-telegram-commands");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() {
        closed = true;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    private void loop() {
        while (!closed) {
            try {
                long wait = pollOnce();
                if (wait > 0) {
                    Thread.sleep(wait);
                }
            } catch (InterruptedException e) {
                if (closed) {
                    return;
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Telegram command poll failed", e);
            }
        }
    }

    /** True when Telegram can send and commands are on. */
    static boolean enabled(NotifyConfig c) {
        return c.telegramState() == NotifyConfig.SinkState.READY && c.telegramCommands && c.chatId.matches("-?\\d+");
    }

    /**
     * One poll: fetches updates and answers them. Returns how long to wait before the next poll (ms): 0 normally,
     * 5 s while commands are off, the back-off after an error.
     */
    long pollOnce() throws InterruptedException {
        NotifyConfig c = config.get();
        if (!enabled(c)) {
            skippedBacklog = false;
            return 5_000;
        }
        String body;
        try {
            body = transport.getUpdates(c.botToken, offset, skippedBacklog ? POLL_TIMEOUT_S : 0);
        } catch (IOException e) {
            backoffMs = backoffMs == 0 ? 1_000 : Math.min(MAX_BACKOFF_MS, backoffMs * 2);
            logClient("poll_error", null, Masking.scrub(e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()), c.botToken));
            return backoffMs;
        }
        JsonArray updates;
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            if (!o.has("ok") || !o.get("ok").getAsBoolean()) {
                backoffMs = backoffMs == 0 ? 1_000 : Math.min(MAX_BACKOFF_MS, backoffMs * 2);
                String d = o.has("description") ? o.get("description").getAsString() : "ok=false";
                logClient("poll_error", null, Masking.scrub(d, c.botToken));
                return backoffMs;
            }
            updates = o.has("result") && o.get("result").isJsonArray() ? o.getAsJsonArray("result") : new JsonArray();
        } catch (RuntimeException e) {
            backoffMs = backoffMs == 0 ? 1_000 : Math.min(MAX_BACKOFF_MS, backoffMs * 2);
            logClient("poll_error", null, "unreadable getUpdates response");
            return backoffMs;
        }
        backoffMs = 0;
        boolean skip = !skippedBacklog;
        for (JsonElement el : updates) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject u = el.getAsJsonObject();
            if (u.has("update_id")) {
                offset = Math.max(offset, u.get("update_id").getAsLong() + 1);
            }
            if (!skip) {
                handle(u, c);
            }
        }
        if (skip) {
            skippedBacklog = true;
            if (!updates.isEmpty()) {
                logClient("skipped_backlog", null, updates.size() + " message(s) from before the bridge started");
            }
        }
        return 0;
    }

    long offset() {
        return offset;
    }

    private void handle(JsonObject u, NotifyConfig c) throws InterruptedException {
        JsonObject msg = u.has("message") && u.get("message").isJsonObject() ? u.getAsJsonObject("message") : null;
        if (msg == null || !msg.has("chat") || !msg.get("chat").isJsonObject()) {
            return;
        }
        JsonObject chat = msg.getAsJsonObject("chat");
        String chatId = chat.has("id") ? chat.get("id").getAsString() : "";
        String type = chat.has("type") ? chat.get("type").getAsString() : "";
        if (!"private".equals(type) || !chatId.equals(c.chatId)) {
            if (ignoredSenders.add(chatId)) {
                logClient("ignored_sender", chatId, "chat type " + type);
            }
            return;
        }
        String text = msg.has("text") && msg.get("text").isJsonPrimitive() ? msg.get("text").getAsString() : "";
        String reply = reply(text, c);
        try {
            transport.sendMessage(c.botToken, c.chatId, Messages.truncate(reply));
        } catch (IOException e) {
            logClient("reply_failed", null, Masking.scrub(e.getClass().getSimpleName(), c.botToken));
        }
    }

    /** The answer to one message (pure apart from {@code pause}). */
    String reply(String text, NotifyConfig c) {
        String word = command(text);
        Machine m = machine;
        logClient("command", null, word.isEmpty() ? "(empty)" : word);
        if (m == null && !word.equals("help")) {
            return "The UGS bridge isn't running (no API). " + help();
        }
        try {
            return switch (word) {
                case "status", "s" -> m.status();
                case "eta", "time", "when" -> m.eta();
                case "tools", "tool" -> m.tools();
                case "log", "events" -> m.log(5);
                case "pause", "hold" -> pause(m, c);
                case "help", "start_help", "?" -> help();
                default -> refused(word) ? AT_THE_MACHINE + " I can only: " + vocabulary(c) + "." : help();
            };
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Telegram command failed: " + word, e);
            return "That didn't work (" + e.getClass().getSimpleName() + "). " + help();
        }
    }

    private String pause(Machine m, NotifyConfig c) {
        if (!c.telegramAllowPause) {
            return "Pause from Telegram is off in notify.json (telegram.allow_pause). " + AT_THE_MACHINE;
        }
        if (!m.jobRunning()) {
            logClient("pause", null, "refused: no job running");
            return "Nothing to pause: no job is running.";
        }
        try {
            String r = m.pause();
            logClient("pause", null, "feed hold sent");
            return "⏸ Feed hold sent (" + r + "). Resume at the machine: check it, then Resume in UGS.";
        } catch (Exception e) {
            logClient("pause", null, "failed: " + e.getClass().getSimpleName());
            return "⚠ Pause failed: " + e.getMessage() + ". Use the machine's controls.";
        }
    }

    /** Machine actions, settings ({@code $…}) and G/M codes. */
    static boolean refused(String word) {
        return REFUSED.contains(word) || word.startsWith("$") || word.startsWith("!") || word.startsWith("~")
                || word.matches("[gmst]\\d.*");
    }

    /** "/Status@my_bot now" gives "status". */
    static String command(String text) {
        if (text == null) {
            return "";
        }
        String t = text.strip().toLowerCase(Locale.ROOT);
        if (t.startsWith("/")) {
            t = t.substring(1);
        }
        int sp = t.indexOf(' ');
        if (sp >= 0) {
            t = t.substring(0, sp);
        }
        int at = t.indexOf('@');
        if (at >= 0) {
            t = t.substring(0, at);
        }
        if (t.equals("start")) {
            return text.strip().startsWith("/") ? "help" : "start"; // Telegram's /start opens the chat
        }
        return t;
    }

    private static String vocabulary(NotifyConfig c) {
        return "status, eta, tools, log" + (c.telegramAllowPause ? ", pause" : "") + ", help";
    }

    private String help() {
        NotifyConfig c = config.get();
        return "Commands: status · eta · tools · log" + (c.telegramAllowPause ? " · pause (feed hold)" : "")
                + " · help. Starting, resuming and moving the machine stay at the machine.";
    }

    private void logClient(String action, String remote, String detail) {
        if (log == null) {
            return;
        }
        JsonObject d = new JsonObject();
        d.addProperty("client", "telegram");
        d.addProperty("action", action);
        if (remote != null) {
            d.addProperty("remote", remote);
        }
        if (detail != null) {
            d.addProperty("detail", detail);
        }
        log.append(LogRecord.Kind.CLIENT, LogRecord.Src.OPERATOR, d);
    }

    /** The real Bot API over HTTPS. */
    public static final class Http implements Transport {
        private final HttpClient client;
        private final URI base;

        public Http(HttpClient client, URI base) {
            this.client = client;
            this.base = base;
        }

        private URI uri(String token, String method) {
            String b = base.toString();
            return URI.create((b.endsWith("/") ? b.substring(0, b.length() - 1) : b) + "/bot" + token + "/" + method);
        }

        @Override
        public String getUpdates(String token, long offset, int timeoutS) throws IOException, InterruptedException {
            JsonObject body = new JsonObject();
            body.addProperty("offset", offset);
            body.addProperty("timeout", timeoutS);
            JsonArray allowed = new JsonArray();
            allowed.add("message");
            body.add("allowed_updates", allowed);
            HttpRequest req = HttpRequest.newBuilder(uri(token, "getUpdates"))
                    .timeout(Duration.ofSeconds(timeoutS + 10L))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", "ugs-bridge-notify/1")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            try {
                return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
            } catch (IOException e) {
                throw new IOException(Masking.scrub(e.getMessage(), token), null);
            }
        }

        @Override
        public void sendMessage(String token, String chatId, String text) throws IOException {
            Sinks.Result r = new Sinks.Telegram(client, base, token, chatId).send(text);
            if (!Sinks.OK.equals(r.status())) {
                throw new IOException(r.detail());
            }
        }
    }
}
