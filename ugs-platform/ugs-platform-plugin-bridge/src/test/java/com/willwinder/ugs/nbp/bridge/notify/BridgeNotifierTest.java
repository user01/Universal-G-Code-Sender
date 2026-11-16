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
import com.willwinder.ugs.nbp.bridge.capture.Capture;
import com.willwinder.ugs.nbp.bridge.capture.CommandOrigins;
import com.willwinder.ugs.nbp.bridge.log.LogFiles;
import com.willwinder.ugs.nbp.bridge.log.LogRecord;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;
import com.willwinder.universalgcodesender.IController;
import com.willwinder.universalgcodesender.communicator.ICommunicator;
import com.willwinder.universalgcodesender.connection.ConnectionDriver;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.events.ControllerStateEvent;
import com.willwinder.universalgcodesender.model.events.StreamEvent;
import com.willwinder.universalgcodesender.model.events.StreamEventType;
import com.willwinder.universalgcodesender.services.interceptor.InterceptorPrompt;
import com.willwinder.universalgcodesender.services.interceptor.InterceptorState;
import com.willwinder.universalgcodesender.services.interceptor.InterceptorStateEvent;
import com.willwinder.universalgcodesender.services.interceptor.ToolChangeInterceptor;
import com.willwinder.universalgcodesender.services.interceptor.UserResponse;
import com.willwinder.universalgcodesender.types.GcodeCommand;
import com.willwinder.universalgcodesender.utils.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static com.willwinder.ugs.nbp.bridge.notify.NotifyConfigTest.TOKEN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The notifier end to end: synthetic UGS events → Capture → session log → triggers → rate limit →
 * fake Telegram/Slack on 127.0.0.1 → notify records.
 */
public class BridgeNotifierTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    FakeChatServer server;
    Path home;
    SessionLog log;
    BridgeNotifier notifier;

    @Before
    public void setUp() throws Exception {
        server = new FakeChatServer();
        home = tmp.newFolder("bridge-home").toPath();
        log = LogFiles.newLog(home, Clock.systemDefaultZone(), System::nanoTime, 10_000);
        log.start();
    }

    @After
    public void tearDown() {
        if (notifier != null) {
            notifier.close();
        }
        log.close();
        server.close();
    }

    void writeConfig(String json) throws Exception {
        NotifyConfig.parse(json).save(home.resolve(NotifyConfig.FILE_NAME));
    }

    BridgeNotifier startNotifier() {
        notifier = new BridgeNotifier(log, home.resolve(NotifyConfig.FILE_NAME), Sinks.newClient(), server.base(),
                server.base(), System::nanoTime, new TriggerEngine.JobModelTools());
        notifier.start();
        return notifier;
    }

    List<JsonObject> notifyData() {
        return log.since(0, Set.of(LogRecord.Kind.NOTIFY), 1000).stream()
                .map(r -> JsonParser.parseString(r.json()).getAsJsonObject().getAsJsonObject("data")).toList();
    }

    List<JsonObject> awaitNotify(Predicate<JsonObject> p, int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            List<JsonObject> l = notifyData().stream().filter(p).toList();
            if (l.size() >= n) {
                return l;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("expected " + n + " notify records, got " + notifyData());
    }

    static Predicate<JsonObject> trigger(String t) {
        return d -> d.get("trigger").getAsString().equals(t);
    }

    @Test
    public void placeholdersAreRefusedAndExplainedOnce() throws Exception {
        String json = Files.readString(NotifyConfigTest.example()).replace("\"enabled\": false", "\"enabled\": true");
        writeConfig(json);
        startNotifier();
        JsonObject cfg = awaitNotify(trigger(Triggers.CONFIG), 1).get(0);
        assertEquals("disabled", cfg.getAsJsonObject("sinks").get("telegram").getAsString());
        assertEquals("disabled", cfg.getAsJsonObject("sinks").get("slack").getAsString());
        assertEquals("placeholder", cfg.getAsJsonObject("config").get("telegram").getAsString());
        assertEquals("warn", cfg.get("level").getAsString());
        assertTrue(cfg.get("text").getAsString(), cfg.get("text").getAsString().contains("REPLACE placeholders"));

        for (int i = 0; i < 3; i++) {
            JsonObject d = notifier.dispatch(Triggers.CLAUDE_MESSAGE, "hello " + i, "info", "claude", false).get(5, TimeUnit.SECONDS);
            assertEquals("disabled", d.getAsJsonObject("sinks").get("telegram").getAsString());
            notifier.reload(); // even a forced reload with the same content doesn't repeat the explanation
        }
        assertEquals(1, notifyData().stream().filter(trigger(Triggers.CONFIG)).count());
        assertTrue(server.requests.isEmpty());
    }

    @Test
    public void claudeMessageGoesToBothSinksAndIsLogged() throws Exception {
        writeConfig(NotifyConfigTest.ready(true, true));
        startNotifier();
        notifier.send(Triggers.CLAUDE_MESSAGE, "Facing pass done, starting the pocket.", "info");
        JsonObject d = awaitNotify(trigger(Triggers.CLAUDE_MESSAGE), 1).get(0);
        assertEquals("💬 Claude: Facing pass done, starting the pocket.", d.get("text").getAsString());
        assertEquals("ok", d.getAsJsonObject("sinks").get("telegram").getAsString());
        assertEquals("ok", d.getAsJsonObject("sinks").get("slack").getAsString());
        assertEquals(2, server.requests.size());
        LogRecord rec = log.since(0, Set.of(LogRecord.Kind.NOTIFY), 100).stream()
                .filter(r -> r.json().contains("claude_message")).findFirst().orElseThrow();
        assertEquals("claude", rec.src());
        // nothing secret in the log
        assertTrue(log.awaitWritten(3000));
        String all = String.join("\n", Files.readAllLines(LogFiles.sessionFiles(home).get(0)));
        assertFalse(all.contains(TOKEN));
        assertFalse(all.contains("xyzFakeSecret123"));
    }

    @Test
    public void triggerToggleOffMeansNoAttemptAndNoRecord() throws Exception {
        String json = NotifyConfigTest.ready(true, false).replace("\"claude_message\":true", "\"claude_message\":false");
        writeConfig(json);
        startNotifier();
        assertNull(notifier.dispatch(Triggers.CLAUDE_MESSAGE, "x", "info", "claude", false).get(5, TimeUnit.SECONDS));
        assertTrue(server.requests.isEmpty());
    }

    @Test
    public void rateLimitAndDedupAreRecorded() throws Exception {
        String json = NotifyConfigTest.ready(true, false).replace("\"rate_limit_per_minute\":6", "\"rate_limit_per_minute\":2");
        writeConfig(json);
        startNotifier();
        assertEquals(2, notifier.config().rateLimitPerMinute);
        notifier.dispatch(Triggers.CLAUDE_MESSAGE, "a", "info", "claude", false).get(5, TimeUnit.SECONDS);
        JsonObject dup = notifier.dispatch(Triggers.CLAUDE_MESSAGE, "a", "info", "claude", false).get(5, TimeUnit.SECONDS);
        notifier.dispatch(Triggers.CLAUDE_MESSAGE, "b", "info", "claude", false).get(5, TimeUnit.SECONDS);
        JsonObject limited = notifier.dispatch(Triggers.CLAUDE_MESSAGE, "c", "info", "claude", false).get(5, TimeUnit.SECONDS);
        JsonObject alert = notifier.dispatch(Triggers.ALARM, "⚠ ALARM 1: hard limit", "alert", "bridge", false).get(5, TimeUnit.SECONDS);
        assertEquals("rate_limited", dup.getAsJsonObject("sinks").get("telegram").getAsString());
        assertEquals("disabled", dup.getAsJsonObject("sinks").get("slack").getAsString());
        assertEquals("duplicate", dup.get("suppressed").getAsString());
        assertEquals("rate_limit", limited.get("suppressed").getAsString());
        assertEquals("ok", alert.getAsJsonObject("sinks").get("telegram").getAsString());
        assertEquals(3, server.requests.size());
    }

    @Test
    public void failedSinkIsRecordedWithMaskedDetail() throws Exception {
        writeConfig(NotifyConfigTest.ready(true, true));
        startNotifier();
        server.enqueue(401, "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");
        JsonObject d = notifier.dispatch(Triggers.CLAUDE_MESSAGE, "x", "info", "claude", false).get(5, TimeUnit.SECONDS);
        assertEquals("failed", d.getAsJsonObject("sinks").get("telegram").getAsString());
        assertEquals("ok", d.getAsJsonObject("sinks").get("slack").getAsString());
        assertEquals("HTTP 401: Unauthorized", d.getAsJsonObject("errors").get("telegram").getAsString());
    }

    @Test
    public void sendTestIgnoresTogglesAndReportsPerSink() throws Exception {
        writeConfig(NotifyConfigTest.ready(true, false));
        startNotifier();
        Map<String, String> res = notifier.sendTest().get(5, TimeUnit.SECONDS);
        assertEquals(Map.of("telegram", "ok", "slack", "disabled"), res);
        assertTrue(server.requests.get(0).body().contains("test notification"));
        // a second test right away is not de-duplicated
        assertEquals("ok", notifier.sendTest().get(5, TimeUnit.SECONDS).get("telegram"));
    }

    @Test
    public void configChangesAreReloaded() throws Exception {
        writeConfig(Files.readString(NotifyConfigTest.example()));
        startNotifier();
        assertEquals(NotifyConfig.SinkState.OFF, notifier.config().telegramState());
        writeConfig(NotifyConfigTest.ready(true, false));
        Files.setLastModifiedTime(home.resolve("notify.json"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        long deadline = System.currentTimeMillis() + 4000;
        while (notifier.config().telegramState() != NotifyConfig.SinkState.READY && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals("picked up by the 1 s check", NotifyConfig.SinkState.READY, notifier.config().telegramState());
        assertEquals(2, notifyData().stream().filter(trigger(Triggers.CONFIG)).count());
    }

    /** Synthetic UGS events through the real Capture into the notifier. */
    @Test
    public void ugsEventsBecomeNotifications() throws Exception {
        writeConfig(NotifyConfigTest.ready(true, true));
        startNotifier();
        Path job = tmp.getRoot().toPath().resolve("two_tools_T02_T21.nc");
        Files.writeString(job, TriggerEngineTest.TWO_TOOLS);

        BackendAPI backend = mock(BackendAPI.class);
        IController controller = mock(IController.class);
        Settings settings = mock(Settings.class);
        when(backend.getController()).thenReturn(controller);
        when(controller.getCommunicator()).thenReturn(mock(ICommunicator.class));
        when(backend.getSettings()).thenReturn(settings);
        when(settings.getConnectionDriver()).thenReturn(ConnectionDriver.TCP);
        when(settings.getPort()).thenReturn("127.0.0.1");
        when(settings.getPortRate()).thenReturn("2323");
        when(backend.getGcodeFile()).thenReturn(job.toFile());

        Capture capture = new Capture(log, new CommandOrigins(), "test");
        capture.attach(backend);
        try {
            capture.UGSEvent(new ControllerStateEvent(ControllerState.CONNECTING, ControllerState.DISCONNECTED));
            capture.UGSEvent(new ControllerStateEvent(ControllerState.IDLE, ControllerState.CONNECTING));
            capture.UGSEvent(new StreamEvent(StreamEventType.STREAM_STARTED));
            GcodeCommand m6 = new GcodeCommand("T21 M6", "T21 M6", "", 10);
            capture.commandSent(m6);
            capture.UGSEvent(new InterceptorStateEvent(InterceptorState.WAITING_FOR_USER, InterceptorState.RUNNING,
                    new ToolChangeInterceptor(() -> true), m6,
                    new InterceptorPrompt(ToolChangeInterceptor.STEP_CHANGE_TOOL, List.of(UserResponse.CONTINUE, UserResponse.ABORT))));
            capture.rawResponseListener("ALARM:4");
            capture.UGSEvent(new StreamEvent(StreamEventType.STREAM_COMPLETE));
            capture.onConnectionClosed();

            JsonObject tool = awaitNotify(trigger(Triggers.TOOL_CHANGE_WAITING), 1).get(0);
            assertEquals("🔧 Tool change: install T21 2-flute upcut spiral 1/8 in, Whiteside RU1600 "
                    + "(two_tools_T02_T21.nc, line 11). Confirm in UGS.", tool.get("text").getAsString());
            assertEquals("⚠ ALARM 4: probe fail (probe already triggered) — two_tools_T02_T21.nc at line 11",
                    awaitNotify(trigger(Triggers.ALARM), 1).get(0).get("text").getAsString());
            String done = awaitNotify(trigger(Triggers.STREAM_FINISHED), 1).get(0).get("text").getAsString();
            assertTrue(done, done.matches("✔ Job finished: two_tools_T02_T21\\.nc — \\d+ s, 16 lines"));
            JsonObject lost = awaitNotify(trigger(Triggers.CONNECTION_LOST), 1).get(0);
            assertEquals("🔌 Connection to the controller lost (127.0.0.1:2323)", lost.get("text").getAsString());
            for (JsonObject d : notifyData()) {
                if (!d.get("trigger").getAsString().equals(Triggers.CONFIG)) {
                    assertEquals(d.toString(), "ok", d.getAsJsonObject("sinks").get("telegram").getAsString());
                    assertEquals(d.toString(), "ok", d.getAsJsonObject("sinks").get("slack").getAsString());
                }
            }
            assertEquals(8, server.requests.size());
        } finally {
            capture.detach();
        }
    }
}
