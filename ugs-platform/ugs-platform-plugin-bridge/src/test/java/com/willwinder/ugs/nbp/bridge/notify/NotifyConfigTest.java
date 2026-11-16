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
import com.willwinder.ugs.nbp.bridge.notify.NotifyConfig.SinkState;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NotifyConfigTest {
    /** Plausible-looking but fake values (no real credentials exist). */
    // Test-only values, assembled at runtime so no credential-shaped literal ever sits in the source
    // (secret scanners match the shape, not whether it is real). Never put real or realistic tokens in tests.
    static final String TOKEN = String.join(":", "100200300", "test".repeat(6));
    static final String CHAT = "987654321";
    static final String WEBHOOK = "https://" + "hooks.slack" + ".com/" + String.join("/", "services", "TEST", "TEST", "test-only");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    static Path example() {
        Path p = Paths.get("../../bridge/config/notify.example.json");
        assertTrue("example config at " + p.toAbsolutePath(), Files.exists(p));
        return p;
    }

    static JsonObject exampleJson() throws Exception {
        return JsonParser.parseString(Files.readString(example())).getAsJsonObject();
    }

    static String ready(boolean telegram, boolean slack) throws Exception {
        JsonObject o = exampleJson();
        o.getAsJsonObject("telegram").addProperty("enabled", telegram);
        o.getAsJsonObject("telegram").addProperty("bot_token", TOKEN);
        o.getAsJsonObject("telegram").addProperty("chat_id", CHAT);
        o.getAsJsonObject("slack").addProperty("enabled", slack);
        o.getAsJsonObject("slack").addProperty("webhook_url", WEBHOOK);
        return o.toString();
    }

    @Test
    public void exampleFileParsesWithBothSinksOff() {
        NotifyConfig c = NotifyConfig.load(example());
        assertEquals(SinkState.OFF, c.telegramState());
        assertEquals(SinkState.OFF, c.slackState());
        assertTrue(c.problems().isEmpty());
        assertTrue(c.streamFinished && c.streamError && c.alarm && c.toolChangeWaiting && c.connectionLost
                && c.claudeAuthorityRequest && c.claudeMessage);
        assertEquals(60, c.feedHoldSeconds);
        assertEquals(0, c.progressEveryPercent);
        assertEquals(6, c.rateLimitPerMinute);
        assertFalse(c.enabled(Triggers.PROGRESS));
        assertTrue(c.enabled(Triggers.FEED_HOLD));
    }

    @Test
    public void enabledWithPlaceholdersIsRefused() throws Exception {
        JsonObject o = exampleJson();
        o.getAsJsonObject("telegram").addProperty("enabled", true);
        o.getAsJsonObject("slack").addProperty("enabled", true);
        NotifyConfig c = NotifyConfig.parse(o);
        assertEquals(SinkState.PLACEHOLDER, c.telegramState());
        assertEquals(SinkState.PLACEHOLDER, c.slackState());
        assertTrue(c.summary(), c.summary().contains("telegram: placeholder"));
        assertTrue(c.summary(), c.summary().contains("REPLACE placeholders"));

        // only the chat id still a placeholder is enough to refuse
        o.getAsJsonObject("telegram").addProperty("bot_token", TOKEN);
        assertEquals(SinkState.PLACEHOLDER, NotifyConfig.parse(o).telegramState());
    }

    @Test
    public void realLookingValuesAreReady() throws Exception {
        NotifyConfig c = NotifyConfig.parse(ready(true, true));
        assertEquals(SinkState.READY, c.telegramState());
        assertEquals(SinkState.READY, c.slackState());
        assertTrue(c.problems().toString(), c.problems().isEmpty());
    }

    @Test
    public void malformedValuesAreInvalid() throws Exception {
        JsonObject o = JsonParser.parseString(ready(true, true)).getAsJsonObject();
        o.getAsJsonObject("telegram").addProperty("bot_token", "not-a-token");
        o.getAsJsonObject("telegram").addProperty("chat_id", "my chat");
        o.getAsJsonObject("slack").addProperty("webhook_url", "http://example.com/hook");
        NotifyConfig c = NotifyConfig.parse(o);
        assertEquals(SinkState.INVALID, c.telegramState());
        assertEquals(SinkState.INVALID, c.slackState());
        assertEquals(3, c.problems().size());
    }

    @Test
    public void chatIdMayBeANumberOrNegativeGroupId() throws Exception {
        JsonObject o = JsonParser.parseString(ready(true, false)).getAsJsonObject();
        o.getAsJsonObject("telegram").addProperty("chat_id", -1001234567890L);
        assertEquals(SinkState.READY, NotifyConfig.parse(o).telegramState());
    }

    @Test
    public void wrongFormatOrJunkDisablesEverything() throws Exception {
        JsonObject o = JsonParser.parseString(ready(true, true)).getAsJsonObject();
        o.addProperty("format", "ugs-bridge-notify/2");
        NotifyConfig c = NotifyConfig.parse(o);
        assertEquals(SinkState.INVALID, c.telegramState());
        assertEquals(SinkState.INVALID, c.slackState());

        NotifyConfig junk = NotifyConfig.parse("{not json");
        assertEquals(SinkState.INVALID, junk.telegramState());
        assertTrue(junk.problems().get(0).contains("not valid JSON"));
    }

    @Test
    public void badTypesFallBackToDefaultsWithProblems() throws Exception {
        JsonObject o = exampleJson();
        o.getAsJsonObject("triggers").addProperty("alarm", "yes");
        o.getAsJsonObject("triggers").addProperty("feed_hold_seconds", -5);
        o.addProperty("rate_limit_per_minute", 0);
        NotifyConfig c = NotifyConfig.parse(o);
        assertTrue(c.alarm);
        assertEquals(60, c.feedHoldSeconds);
        assertEquals(6, c.rateLimitPerMinute);
        assertEquals(3, c.problems().size());
    }

    @Test
    public void missingFileGivesDisabledDefaults() {
        NotifyConfig c = NotifyConfig.load(tmp.getRoot().toPath().resolve("notify.json"));
        assertEquals(SinkState.OFF, c.telegramState());
        assertTrue(c.problems().get(0).contains("not found"));
    }

    @Test
    public void toStringAndSummaryNeverContainSecrets() throws Exception {
        NotifyConfig c = NotifyConfig.parse(ready(true, true));
        String s = c.toString() + c.summary();
        assertFalse(s, s.contains(TOKEN));
        assertFalse(s, s.contains(TOKEN.substring(TOKEN.indexOf(":") + 1)));
        assertFalse(s, s.contains("test-only"));
        assertFalse(s, s.contains("T000FAKE"));
        assertTrue(s, s.contains("https://hooks.slack.com/****"));
    }

    @Test
    public void masking() {
        assertEquals("1002…(" + TOKEN.length() + ")…st", Masking.token(TOKEN));
        assertEquals("(placeholder)", Masking.token("123456789:REPLACE_WITH_BOT_TOKEN_FROM_BOTFATHER"));
        assertEquals("****", Masking.token("short"));
        assertEquals("…321", Masking.chatId(CHAT));
        assertEquals("https://hooks.slack.com/****", Masking.url(WEBHOOK));
        assertEquals("(placeholder)", Masking.url("https://hooks.slack.com/services/REPLACE/WITH/WEBHOOK"));
        assertEquals("failed for **** at /bot****/sendMessage",
                Masking.scrub("failed for " + WEBHOOK + " at /bot" + TOKEN + "/sendMessage", TOKEN, WEBHOOK));
    }

    @Test
    public void saveWrites0600AndKeepsUnknownKeys() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("sub/notify.json");
        JsonObject o = JsonParser.parseString(ready(true, false)).getAsJsonObject();
        o.addProperty("operator_note", "keep me");
        NotifyConfig.Builder b = NotifyConfig.parse(o).toBuilder();
        b.progressEveryPercent = 25;
        b.alarm = false;
        NotifyConfig c = b.build();
        c.save(file);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        NotifyConfig back = NotifyConfig.load(file);
        assertEquals(25, back.progressEveryPercent);
        assertFalse(back.alarm);
        assertEquals(SinkState.READY, back.telegramState());
        assertTrue(Files.readString(file).contains("\"operator_note\": \"keep me\""));
        assertFalse(Files.exists(file.resolveSibling("notify.json.tmp")));
    }

    @Test
    public void saveTightensExistingLoosePermissions() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("notify.json");
        Files.writeString(file, "{}");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        NotifyConfig.defaults().save(file);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }
}
