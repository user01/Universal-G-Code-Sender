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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * notify.json ({@code ugs-bridge-notify/1}, template {@code bridge/config/notify.example.json}).
 * <p>
 * Parsing never throws: problems are collected and each sink gets a {@link SinkState}. A sink only sends when it
 * is {@link SinkState#READY}: enabled, no {@code REPLACE…} placeholder left and the values look valid.
 * {@link #toString()} and {@link #summary()} never contain the bot token or the webhook URL.
 */
public final class NotifyConfig {
    public static final String FORMAT = "ugs-bridge-notify/1";
    public static final String FILE_NAME = "notify.json";
    public static final int DEFAULT_RATE_LIMIT = 6;
    public static final int DEFAULT_DEDUP_SECONDS = 120;
    public static final int DEFAULT_FEED_HOLD_SECONDS = 60;
    /** Progress messages every 25 % of the estimated machine time unless notify.json says otherwise (D25). */
    public static final int DEFAULT_PROGRESS_PERCENT = 25;

    private static final Pattern BOT_TOKEN = Pattern.compile("^\\d{3,}:[A-Za-z0-9_-]{20,}$");
    private static final Pattern CHAT_ID = Pattern.compile("^(-?\\d{1,20}|@[A-Za-z][A-Za-z0-9_]{3,})$");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Whether a sink can send. */
    public enum SinkState {
        /** {@code enabled: false} (or the sink block is missing). */
        OFF,
        /** Enabled but a value still contains {@code REPLACE}. */
        PLACEHOLDER,
        /** Enabled but a value is malformed (or the whole file is). */
        INVALID,
        READY;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    // sinks
    public final boolean telegramEnabled;
    public final String botToken;
    public final String chatId;
    /** Answer {@code status}, {@code eta}, ... sent to the bot from the configured chat (PLAN D26). */
    public final boolean telegramCommands;
    /** Accept {@code pause} (feed hold) from the configured chat. */
    public final boolean telegramAllowPause;
    public final boolean slackEnabled;
    public final String webhookUrl;
    // triggers
    public final boolean streamFinished;
    public final boolean streamError;
    public final boolean alarm;
    public final int feedHoldSeconds;
    public final boolean toolChangeWaiting;
    public final boolean connectionLost;
    public final boolean claudeAuthorityRequest;
    public final boolean claudeMessage;
    public final int progressEveryPercent;
    /** Progress message every N minutes of machine time (0 = off). */
    public final int progressEveryMinutes;
    // limits
    public final int rateLimitPerMinute;
    public final int dedupSeconds;

    private final List<String> problems;
    private final SinkState telegramState;
    private final SinkState slackState;
    private final JsonObject source;

    private NotifyConfig(Builder b, List<String> problems, JsonObject source) {
        this.telegramEnabled = b.telegramEnabled;
        this.botToken = b.botToken;
        this.chatId = b.chatId;
        this.telegramCommands = b.telegramCommands;
        this.telegramAllowPause = b.telegramAllowPause;
        this.slackEnabled = b.slackEnabled;
        this.webhookUrl = b.webhookUrl;
        this.streamFinished = b.streamFinished;
        this.streamError = b.streamError;
        this.alarm = b.alarm;
        this.feedHoldSeconds = b.feedHoldSeconds;
        this.toolChangeWaiting = b.toolChangeWaiting;
        this.connectionLost = b.connectionLost;
        this.claudeAuthorityRequest = b.claudeAuthorityRequest;
        this.claudeMessage = b.claudeMessage;
        this.progressEveryPercent = b.progressEveryPercent;
        this.progressEveryMinutes = b.progressEveryMinutes;
        this.rateLimitPerMinute = b.rateLimitPerMinute;
        this.dedupSeconds = b.dedupSeconds;
        this.source = source == null ? new JsonObject() : source.deepCopy();
        List<String> p = new ArrayList<>(problems);
        boolean fileInvalid = b.fileInvalid;
        this.telegramState = fileInvalid ? SinkState.INVALID : telegramState(telegramEnabled, botToken, chatId, p);
        this.slackState = fileInvalid ? SinkState.INVALID : slackState(slackEnabled, webhookUrl, p);
        this.problems = List.copyOf(p);
    }

    /** Defaults of the example file: both sinks off, every trigger on, progress every 25 %. */
    public static NotifyConfig defaults() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A builder pre-filled with this config (the panel edits through it). */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.telegramEnabled = telegramEnabled;
        b.botToken = botToken;
        b.chatId = chatId;
        b.telegramCommands = telegramCommands;
        b.telegramAllowPause = telegramAllowPause;
        b.slackEnabled = slackEnabled;
        b.webhookUrl = webhookUrl;
        b.streamFinished = streamFinished;
        b.streamError = streamError;
        b.alarm = alarm;
        b.feedHoldSeconds = feedHoldSeconds;
        b.toolChangeWaiting = toolChangeWaiting;
        b.connectionLost = connectionLost;
        b.claudeAuthorityRequest = claudeAuthorityRequest;
        b.claudeMessage = claudeMessage;
        b.progressEveryPercent = progressEveryPercent;
        b.progressEveryMinutes = progressEveryMinutes;
        b.rateLimitPerMinute = rateLimitPerMinute;
        b.dedupSeconds = dedupSeconds;
        b.source = source;
        return b;
    }

    // ---------------------------------------------------------------- parsing

    public static NotifyConfig parse(String json) {
        JsonElement el;
        try {
            el = JsonParser.parseString(json);
        } catch (JsonParseException | IllegalStateException e) {
            return invalid("notify.json is not valid JSON");
        }
        if (el == null || !el.isJsonObject()) {
            return invalid("notify.json must be a JSON object");
        }
        return parse(el.getAsJsonObject());
    }

    public static NotifyConfig parse(JsonObject o) {
        List<String> problems = new ArrayList<>();
        Builder b = new Builder();
        b.source = o;
        String format = str(o, "format", null, problems);
        if (!FORMAT.equals(format)) {
            problems.add("format must be \"" + FORMAT + "\"");
            b.fileInvalid = true;
        }
        JsonObject tg = obj(o, "telegram", problems);
        if (tg != null) {
            b.telegramEnabled = bool(tg, "enabled", false, problems, "telegram.enabled");
            b.botToken = str(tg, "bot_token", "", problems);
            b.chatId = str(tg, "chat_id", "", problems);
            b.telegramCommands = bool(tg, "commands_enabled", true, problems, "telegram.commands_enabled");
            b.telegramAllowPause = bool(tg, "allow_pause", true, problems, "telegram.allow_pause");
        }
        JsonObject sl = obj(o, "slack", problems);
        if (sl != null) {
            b.slackEnabled = bool(sl, "enabled", false, problems, "slack.enabled");
            b.webhookUrl = str(sl, "webhook_url", "", problems);
        }
        JsonObject t = obj(o, "triggers", problems);
        if (t != null) {
            b.streamFinished = bool(t, Triggers.STREAM_FINISHED, true, problems, "triggers." + Triggers.STREAM_FINISHED);
            b.streamError = bool(t, Triggers.STREAM_ERROR, true, problems, "triggers." + Triggers.STREAM_ERROR);
            b.alarm = bool(t, Triggers.ALARM, true, problems, "triggers." + Triggers.ALARM);
            b.feedHoldSeconds = intIn(t, "feed_hold_seconds", DEFAULT_FEED_HOLD_SECONDS, 0, 86_400, problems);
            b.toolChangeWaiting = bool(t, Triggers.TOOL_CHANGE_WAITING, true, problems, "triggers." + Triggers.TOOL_CHANGE_WAITING);
            b.connectionLost = bool(t, Triggers.CONNECTION_LOST, true, problems, "triggers." + Triggers.CONNECTION_LOST);
            b.claudeAuthorityRequest = bool(t, Triggers.CLAUDE_AUTHORITY_REQUEST, true, problems,
                    "triggers." + Triggers.CLAUDE_AUTHORITY_REQUEST);
            b.claudeMessage = bool(t, Triggers.CLAUDE_MESSAGE, true, problems, "triggers." + Triggers.CLAUDE_MESSAGE);
            b.progressEveryPercent = intIn(t, "progress_every_percent", DEFAULT_PROGRESS_PERCENT, 0, 100, problems);
            b.progressEveryMinutes = intIn(t, "progress_every_minutes", 0, 0, 1440, problems);
        }
        b.rateLimitPerMinute = intIn(o, "rate_limit_per_minute", DEFAULT_RATE_LIMIT, 1, 60, problems);
        b.dedupSeconds = intIn(o, "dedup_seconds", DEFAULT_DEDUP_SECONDS, 0, 3600, problems);
        return new NotifyConfig(b, problems, o);
    }

    /** Reads {@code file}; a missing file gives {@link #defaults()} with a problem noted. */
    public static NotifyConfig load(Path file) {
        if (!Files.exists(file)) {
            Builder b = new Builder();
            return new NotifyConfig(b, List.of(FILE_NAME + " not found (template: bridge/config/notify.example.json)"), null);
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return invalid("could not read " + FILE_NAME + ": " + e.getClass().getSimpleName());
        }
    }

    private static NotifyConfig invalid(String problem) {
        Builder b = new Builder();
        b.fileInvalid = true;
        return new NotifyConfig(b, List.of(problem), null);
    }

    // ---------------------------------------------------------------- state

    public SinkState telegramState() {
        return telegramState;
    }

    public SinkState slackState() {
        return slackState;
    }

    /** Problems found while parsing/validating (never contains secrets). */
    public List<String> problems() {
        return problems;
    }

    /** Whether the toggle for {@code trigger} is on. Unknown triggers (and {@code test}) are allowed. */
    public boolean enabled(String trigger) {
        return switch (trigger) {
            case Triggers.STREAM_FINISHED -> streamFinished;
            case Triggers.STREAM_ERROR -> streamError;
            case Triggers.ALARM -> alarm;
            case Triggers.FEED_HOLD -> feedHoldSeconds > 0;
            case Triggers.TOOL_CHANGE_WAITING -> toolChangeWaiting;
            case Triggers.CONNECTION_LOST -> connectionLost;
            case Triggers.CLAUDE_AUTHORITY_REQUEST -> claudeAuthorityRequest;
            case Triggers.CLAUDE_MESSAGE -> claudeMessage;
            case Triggers.PROGRESS -> progressEveryPercent > 0 || progressEveryMinutes > 0;
            default -> true;
        };
    }

    /** One line for the panel and the {@code config} notify record, e.g. "telegram: placeholder; slack: off". */
    public String summary() {
        StringBuilder s = new StringBuilder("telegram: ").append(telegramState.label())
                .append("; slack: ").append(slackState.label());
        if (!problems.isEmpty()) {
            s.append(" (").append(String.join("; ", problems)).append(')');
        }
        return s.toString();
    }

    @Override
    public String toString() {
        return "NotifyConfig{telegram=" + telegramState.label() + " token=" + Masking.token(botToken)
                + " chat=" + Masking.chatId(chatId) + ", slack=" + slackState.label() + " webhook="
                + Masking.url(webhookUrl) + ", problems=" + problems + "}";
    }

    private static SinkState telegramState(boolean enabled, String token, String chat, List<String> problems) {
        if (!enabled) {
            return SinkState.OFF;
        }
        if (isPlaceholder(token) || isPlaceholder(chat)) {
            problems.add("telegram: bot_token/chat_id still contain REPLACE placeholders");
            return SinkState.PLACEHOLDER;
        }
        boolean ok = true;
        if (!BOT_TOKEN.matcher(token).matches()) {
            problems.add("telegram: bot_token does not look like <digits>:<secret> from BotFather");
            ok = false;
        }
        if (!CHAT_ID.matcher(chat).matches()) {
            problems.add("telegram: chat_id must be a number (e.g. 123456789 or -100…) or @channel");
            ok = false;
        }
        return ok ? SinkState.READY : SinkState.INVALID;
    }

    private static SinkState slackState(boolean enabled, String url, List<String> problems) {
        if (!enabled) {
            return SinkState.OFF;
        }
        if (isPlaceholder(url)) {
            problems.add("slack: webhook_url still contains REPLACE placeholders");
            return SinkState.PLACEHOLDER;
        }
        if (!isSlackWebhook(url)) {
            problems.add("slack: webhook_url must be an https://hooks.slack.com/… incoming-webhook URL");
            return SinkState.INVALID;
        }
        return SinkState.READY;
    }

    static boolean isPlaceholder(String v) {
        return v == null || v.isBlank() || v.toUpperCase(Locale.ROOT).contains("REPLACE");
    }

    static boolean isSlackWebhook(String url) {
        try {
            URI u = new URI(url);
            String host = u.getHost();
            return "https".equals(u.getScheme()) && host != null
                    && (host.equals("hooks.slack.com") || host.endsWith(".slack.com") || host.endsWith(".slack-gov.com"))
                    && u.getPath() != null && u.getPath().length() > 1;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- writing

    /** The JSON to save: the original object (unknown keys kept) with this config's values. */
    public JsonObject toJson() {
        JsonObject o = source.deepCopy();
        o.addProperty("format", FORMAT);
        JsonObject tg = o.has("telegram") && o.get("telegram").isJsonObject() ? o.getAsJsonObject("telegram") : new JsonObject();
        tg.addProperty("enabled", telegramEnabled);
        tg.addProperty("bot_token", botToken);
        tg.addProperty("chat_id", chatId);
        tg.addProperty("commands_enabled", telegramCommands);
        tg.addProperty("allow_pause", telegramAllowPause);
        o.add("telegram", tg);
        JsonObject sl = o.has("slack") && o.get("slack").isJsonObject() ? o.getAsJsonObject("slack") : new JsonObject();
        sl.addProperty("enabled", slackEnabled);
        sl.addProperty("webhook_url", webhookUrl);
        o.add("slack", sl);
        JsonObject t = o.has("triggers") && o.get("triggers").isJsonObject() ? o.getAsJsonObject("triggers") : new JsonObject();
        t.addProperty(Triggers.STREAM_FINISHED, streamFinished);
        t.addProperty(Triggers.STREAM_ERROR, streamError);
        t.addProperty(Triggers.ALARM, alarm);
        t.addProperty("feed_hold_seconds", feedHoldSeconds);
        t.addProperty(Triggers.TOOL_CHANGE_WAITING, toolChangeWaiting);
        t.addProperty(Triggers.CONNECTION_LOST, connectionLost);
        t.addProperty(Triggers.CLAUDE_AUTHORITY_REQUEST, claudeAuthorityRequest);
        t.addProperty(Triggers.CLAUDE_MESSAGE, claudeMessage);
        t.addProperty("progress_every_percent", progressEveryPercent);
        t.addProperty("progress_every_minutes", progressEveryMinutes);
        o.add("triggers", t);
        o.addProperty("rate_limit_per_minute", rateLimitPerMinute);
        if (o.has("dedup_seconds") || dedupSeconds != DEFAULT_DEDUP_SECONDS) {
            o.addProperty("dedup_seconds", dedupSeconds);
        }
        return o;
    }

    /** Writes the file atomically with mode 0600 (owner read/write only). */
    public void save(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        Set<PosixFilePermission> rw = PosixFilePermissions.fromString("rw-------");
        try {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(rw));
        } catch (UnsupportedOperationException | FileAlreadyExistsException e) {
            Files.writeString(tmp, "");
        }
        Files.writeString(tmp, GSON.toJson(toJson()) + "\n", StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tmp, rw);
        } catch (UnsupportedOperationException e) {
            // not a POSIX file system
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---------------------------------------------------------------- json helpers

    private static JsonObject obj(JsonObject o, String key, List<String> problems) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonObject()) {
            problems.add(key + " must be an object");
            return null;
        }
        return e.getAsJsonObject();
    }

    private static String str(JsonObject o, String key, String dflt, List<String> problems) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return dflt;
        }
        if (e.isJsonPrimitive()) {
            return e.getAsString().trim(); // numbers are accepted for chat_id
        }
        problems.add(key + " must be a string");
        return dflt;
    }

    private static boolean bool(JsonObject o, String key, boolean dflt, List<String> problems, String path) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return dflt;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
            return e.getAsBoolean();
        }
        problems.add(path + " must be true or false");
        return dflt;
    }

    private static int intIn(JsonObject o, String key, int dflt, int min, int max, List<String> problems) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return dflt;
        }
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) {
                double d = p.getAsDouble();
                if (d == Math.rint(d) && d >= min && d <= max) {
                    return (int) d;
                }
            }
        }
        problems.add(key + " must be a whole number from " + min + " to " + max);
        return dflt;
    }

    /** Mutable copy used by the settings panel and tests. */
    public static final class Builder {
        boolean fileInvalid;
        JsonObject source;
        public boolean telegramEnabled;
        public String botToken = "123456789:REPLACE_WITH_BOT_TOKEN_FROM_BOTFATHER";
        public String chatId = "REPLACE_WITH_CHAT_ID";
        public boolean telegramCommands = true;
        public boolean telegramAllowPause = true;
        public boolean slackEnabled;
        public String webhookUrl = "https://hooks.slack.com/services/REPLACE/WITH/WEBHOOK";
        public boolean streamFinished = true;
        public boolean streamError = true;
        public boolean alarm = true;
        public int feedHoldSeconds = DEFAULT_FEED_HOLD_SECONDS;
        public boolean toolChangeWaiting = true;
        public boolean connectionLost = true;
        public boolean claudeAuthorityRequest = true;
        public boolean claudeMessage = true;
        public int progressEveryPercent = DEFAULT_PROGRESS_PERCENT;
        public int progressEveryMinutes;
        public int rateLimitPerMinute = DEFAULT_RATE_LIMIT;
        public int dedupSeconds = DEFAULT_DEDUP_SECONDS;

        /** Validates by round-tripping through {@link #parse(JsonObject)}. */
        public NotifyConfig build() {
            NotifyConfig raw = new NotifyConfig(this, List.of(), source);
            return parse(raw.toJson());
        }
    }
}
