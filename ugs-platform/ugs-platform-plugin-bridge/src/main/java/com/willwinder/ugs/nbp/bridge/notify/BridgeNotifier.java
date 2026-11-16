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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.willwinder.ugs.nbp.bridge.log.LogRecord;
import com.willwinder.ugs.nbp.bridge.log.LogRecord.Kind;
import com.willwinder.ugs.nbp.bridge.log.LogRecord.Src;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;
import com.willwinder.ugs.nbp.bridge.notify.NotifyConfig.SinkState;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends operator notifications to Telegram and/or Slack (PLAN D5, SPEC §4.6).
 * <ul>
 *     <li>Listens to the session log (which {@code Capture} fills from UGS events) and maps records to triggers
 *     on its own thread; the log listener only enqueues, so UGS threads are never blocked.</li>
 *     <li>HTTP calls run on a separate sender thread (bounded queue), with timeouts and one retry.</li>
 *     <li>notify.json is re-read when it changes (checked every second) or on {@link #reload()}.</li>
 *     <li>Every attempt is logged as a {@code notify} record with a per-sink result
 *     ({@code ok|failed|disabled|rate_limited}). Tokens and webhook URLs are never logged.</li>
 * </ul>
 */
public final class BridgeNotifier implements Notifier, AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(BridgeNotifier.class.getName());
    static final int SEND_QUEUE = 64;
    private static final String[] SINKS = {"telegram", "slack"};

    private final SessionLog log;
    private final Path configFile;
    private final HttpClient client;
    private final URI telegramBase;
    private final URI slackBase;
    private final LongSupplier nanos;
    private final RateLimiter limiter = new RateLimiter();
    private final TriggerEngine engine;
    private final ScheduledExecutorService events;
    private final ThreadPoolExecutor sender;
    private final Consumer<LogRecord> listener = this::onLogRecord;

    private volatile NotifyConfig config = NotifyConfig.defaults();
    private Object fileStamp = "unread"; // guarded by this
    private String loggedSummary;        // guarded by this
    private volatile boolean closed;

    /** Creates and starts the notifier for the bridge home of {@code log}. */
    public static BridgeNotifier start(SessionLog log) {
        BridgeNotifier n = new BridgeNotifier(log, log.home().resolve(NotifyConfig.FILE_NAME), Sinks.newClient(),
                Sinks.TELEGRAM_API, null, System::nanoTime, new TriggerEngine.JobModelTools());
        n.start();
        return n;
    }

    /**
     * @param telegramBase Bot API base ({@link Sinks#TELEGRAM_API}, or a loopback fake in tests)
     * @param slackBase    if not null, replaces scheme/host/port of the webhook URL (tests)
     */
    BridgeNotifier(SessionLog log, Path configFile, HttpClient client, URI telegramBase, URI slackBase,
                   LongSupplier nanos, TriggerEngine.ToolLookup tools) {
        this.log = log;
        this.configFile = configFile;
        this.client = client;
        this.telegramBase = telegramBase;
        this.slackBase = slackBase;
        this.nanos = nanos;
        this.engine = new TriggerEngine(() -> config, nanos, tools);
        this.events = Executors.newSingleThreadScheduledExecutor(daemon("ugs-bridge-notify-events"));
        this.sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(SEND_QUEUE),
                daemon("ugs-bridge-notify-send"));
    }

    void start() {
        reloadIfChanged();
        if (log != null) {
            log.addListener(listener);
        }
        events.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        closed = true;
        if (log != null) {
            log.removeListener(listener);
        }
        events.shutdownNow();
        sender.shutdown();
        try {
            events.awaitTermination(1, TimeUnit.SECONDS);
            if (!sender.awaitTermination(2, TimeUnit.SECONDS)) {
                sender.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public Path configFile() {
        return configFile;
    }

    /** The configuration in effect. */
    public NotifyConfig config() {
        return config;
    }

    /** Re-reads notify.json now (panel "Reload"/"Save"). */
    public NotifyConfig reload() {
        synchronized (this) {
            fileStamp = "forced";
        }
        return reloadIfChanged();
    }

    // ---------------------------------------------------------------- Notifier (API)

    /**
     * For the API ({@code POST /notify}) and other bridge code. {@code claude_message} and
     * {@code claude_authority_request} texts get a "Claude:" prefix. Never blocks.
     */
    @Override
    public void send(String trigger, String text, String level) {
        String t = trigger == null || trigger.isBlank() ? Triggers.CLAUDE_MESSAGE : trigger;
        String body = Messages.truncate(text);
        if (Triggers.CLAUDE_MESSAGE.equals(t)) {
            body = Messages.claudeMessage(body);
        } else if (Triggers.CLAUDE_AUTHORITY_REQUEST.equals(t)) {
            body = Messages.claudeAuthorityRequest(body);
        }
        String src = t.startsWith("claude") ? Src.CLAUDE : Src.BRIDGE;
        dispatch(t, body, Triggers.level(level), src, false);
    }

    /** "Send test" button: ignores toggles, rate limit and de-dup. Completes with sink → result. */
    public CompletableFuture<Map<String, String>> sendTest() {
        reloadIfChanged();
        return dispatch(Triggers.TEST, Messages.test(), Triggers.INFO, Src.OPERATOR, true)
                .thenApply(BridgeNotifier::describe);
    }

    // ---------------------------------------------------------------- pipeline

    private void onLogRecord(LogRecord r) {
        if (closed || Kind.NOTIFY.equals(r.kind()) || !TriggerEngine.relevant(r.kind())) {
            return;
        }
        try {
            events.execute(() -> handle(r));
        } catch (RejectedExecutionException e) {
            // shutting down
        }
    }

    private void handle(LogRecord r) {
        try {
            JsonObject rec = JsonParser.parseString(r.json()).getAsJsonObject();
            for (TriggerEngine.Notification n : engine.onRecord(rec)) {
                dispatch(n.trigger(), n.text(), n.level(), Src.BRIDGE, false);
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Notification trigger failed for record " + r.seq(), e);
        }
    }

    /** Test hook: feed one record synchronously (bypasses the log listener). */
    List<CompletableFuture<JsonObject>> handleNow(JsonObject record) {
        List<CompletableFuture<JsonObject>> out = new ArrayList<>();
        for (TriggerEngine.Notification n : engine.onRecord(record)) {
            out.add(dispatch(n.trigger(), n.text(), n.level(), Src.BRIDGE, false));
        }
        return out;
    }

    /** Test hook: run the timers synchronously. */
    List<CompletableFuture<JsonObject>> tickNow() {
        List<CompletableFuture<JsonObject>> out = new ArrayList<>();
        for (TriggerEngine.Notification n : engine.tick()) {
            out.add(dispatch(n.trigger(), n.text(), n.level(), Src.BRIDGE, false));
        }
        return out;
    }

    private void tick() {
        try {
            reloadIfChanged();
            for (TriggerEngine.Notification n : engine.tick()) {
                dispatch(n.trigger(), n.text(), n.level(), Src.BRIDGE, false);
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Notification timer failed", e);
        }
    }

    /**
     * Applies the toggle, rate limit and de-dup, then sends on the sender thread.
     *
     * @return completes with the {@code data} of the notify record (or null if the trigger is toggled off)
     */
    CompletableFuture<JsonObject> dispatch(String trigger, String text, String level, String src, boolean bypass) {
        NotifyConfig cfg = config;
        if (!bypass && !cfg.enabled(trigger)) {
            return CompletableFuture.completedFuture(null);
        }
        Map<String, SinkState> states = new LinkedHashMap<>();
        states.put("telegram", cfg.telegramState());
        states.put("slack", cfg.slackState());
        boolean anyReady = states.containsValue(SinkState.READY);

        JsonObject data = new JsonObject();
        data.addProperty("trigger", trigger);
        data.addProperty("text", text);
        data.addProperty("level", level);
        JsonObject sinks = new JsonObject();
        data.add("sinks", sinks);

        if (!anyReady) {
            states.keySet().forEach(k -> sinks.addProperty(k, Sinks.DISABLED));
            return CompletableFuture.completedFuture(record(src, data));
        }
        if (!bypass) {
            RateLimiter.Decision d = limiter.check(text, level, nanos.getAsLong(), cfg.rateLimitPerMinute, cfg.dedupSeconds);
            if (d != RateLimiter.Decision.SEND) {
                states.forEach((k, s) -> sinks.addProperty(k, s == SinkState.READY ? Sinks.RATE_LIMITED : Sinks.DISABLED));
                data.addProperty("suppressed", d == RateLimiter.Decision.DUPLICATE ? "duplicate" : "rate_limit");
                return CompletableFuture.completedFuture(record(src, data));
            }
        }
        List<Sinks.Sink> active = new ArrayList<>();
        if (cfg.telegramState() == SinkState.READY) {
            active.add(new Sinks.Telegram(client, telegramBase, cfg.botToken, cfg.chatId));
        }
        if (cfg.slackState() == SinkState.READY) {
            active.add(new Sinks.Slack(client, cfg.webhookUrl, slackBase));
        }
        CompletableFuture<JsonObject> done = new CompletableFuture<>();
        try {
            sender.execute(() -> {
                JsonObject errors = new JsonObject();
                for (String name : SINKS) {
                    Sinks.Sink sink = active.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
                    if (sink == null) {
                        sinks.addProperty(name, Sinks.DISABLED);
                        continue;
                    }
                    Sinks.Result res = sink.send(text);
                    sinks.addProperty(name, res.status());
                    if (res.detail() != null) {
                        errors.addProperty(name, Masking.scrub(res.detail(), cfg.botToken, cfg.webhookUrl));
                    }
                }
                if (errors.size() > 0) {
                    data.add("errors", errors);
                }
                done.complete(record(src, data));
            });
        } catch (RejectedExecutionException e) {
            states.forEach((k, s) -> sinks.addProperty(k, s == SinkState.READY ? Sinks.FAILED : Sinks.DISABLED));
            JsonObject errors = new JsonObject();
            errors.addProperty("queue", closed ? "notifier closed" : "send queue full");
            data.add("errors", errors);
            done.complete(record(src, data));
        }
        return done;
    }

    private JsonObject record(String src, JsonObject data) {
        if (log != null) {
            log.append(Kind.NOTIFY, src, data);
        }
        return data;
    }

    // ---------------------------------------------------------------- config

    /** Reloads notify.json if its modification time or size changed; logs a config record on a state change. */
    synchronized NotifyConfig reloadIfChanged() {
        Object stamp = stamp(configFile);
        if (!Objects.equals(stamp, fileStamp)) {
            fileStamp = stamp;
            config = NotifyConfig.load(configFile);
            LOGGER.info(() -> "ugs-bridge notifications: " + config);
        }
        String summary = config.summary();
        if (!summary.equals(loggedSummary)) {
            loggedSummary = summary;
            logConfigState(config);
        }
        return config;
    }

    private void logConfigState(NotifyConfig cfg) {
        JsonObject data = new JsonObject();
        data.addProperty("trigger", Triggers.CONFIG);
        boolean problem = cfg.telegramState() == SinkState.PLACEHOLDER || cfg.telegramState() == SinkState.INVALID
                || cfg.slackState() == SinkState.PLACEHOLDER || cfg.slackState() == SinkState.INVALID;
        data.addProperty("text", "notifications " + cfg.summary());
        data.addProperty("level", problem ? Triggers.WARN : Triggers.INFO);
        JsonObject sinks = new JsonObject();
        JsonObject state = new JsonObject();
        if (cfg.telegramState() != SinkState.READY) {
            sinks.addProperty("telegram", Sinks.DISABLED);
        }
        if (cfg.slackState() != SinkState.READY) {
            sinks.addProperty("slack", Sinks.DISABLED);
        }
        state.addProperty("telegram", cfg.telegramState().label());
        state.addProperty("slack", cfg.slackState().label());
        data.add("sinks", sinks);
        data.add("config", state);
        data.addProperty("file", configFile.toString());
        record(Src.BRIDGE, data);
    }

    private static Object stamp(Path file) {
        try {
            BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
            return a.lastModifiedTime().toMillis() + ":" + a.size();
        } catch (IOException e) {
            return "missing";
        }
    }

    // ---------------------------------------------------------------- helpers

    static Map<String, String> describe(JsonObject data) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonObject sinks = data.getAsJsonObject("sinks");
        JsonObject errors = data.has("errors") ? data.getAsJsonObject("errors") : new JsonObject();
        for (String k : sinks.keySet()) {
            String v = sinks.get(k).getAsString();
            out.put(k, errors.has(k) ? v + " (" + errors.get(k).getAsString() + ")" : v);
        }
        return out;
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
