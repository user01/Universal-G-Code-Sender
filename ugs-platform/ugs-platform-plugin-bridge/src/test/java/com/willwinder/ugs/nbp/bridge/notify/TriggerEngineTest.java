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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Record → notification mapping with synthetic session-log records. */
public class TriggerEngineTest {
    static final String TWO_TOOLS = """
            (TOOL [FIRST]: T02 2-flute downcut spiral 1/4 in, Whiteside RD2100   feed 1778 / plunge 508 mm/min)
            G21 G90 G94 G17
            G54
            T2 M6
            S18000 M3
            (---- OP 01 | T02 | pocket | a test pocket ----)
            G0 X10 Y10
            G1 Z-2 F508
            M5
            (TOOL [SECOND]: T21 2-flute upcut spiral 1/8 in, Whiteside RU1600   feed 1143 / plunge 381 mm/min)
            T21 M6
            S18000 M3
            (---- OP 02 | T21 | finish | a test finish ----)
            G0 X30 Y30
            M5
            M2
            """;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    final AtomicLong nanos = new AtomicLong(TimeUnit.SECONDS.toNanos(1000));
    final AtomicReference<NotifyConfig> config = new AtomicReference<>(NotifyConfig.defaults());
    final TriggerEngine engine = new TriggerEngine(config::get, nanos::get, new TriggerEngine.JobModelTools());

    static JsonObject rec(String kind, double mono, String data) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        o.addProperty("mono", mono);
        o.add("data", JsonParser.parseString(data).getAsJsonObject());
        return o;
    }

    List<TriggerEngine.Notification> feed(String kind, double mono, String data) {
        return engine.onRecord(rec(kind, mono, data));
    }

    void advance(double seconds) {
        nanos.addAndGet((long) (seconds * 1e9));
    }

    static TriggerEngine.Notification one(List<TriggerEngine.Notification> l) {
        assertEquals(l.toString(), 1, l.size());
        return l.get(0);
    }

    void startStream(String path, int lines, double mono) {
        assertTrue(feed("event", mono, "{\"type\":\"stream\",\"event\":\"started\"}").isEmpty());
        assertTrue(feed("file", mono, "{\"action\":\"stream_start\",\"path\":\"" + path + "\",\"lines\":" + lines + "}").isEmpty());
    }

    @Test
    public void streamFinishedHasNameDurationAndLines() {
        feed("session", 1, "{\"event\":\"connect\",\"port\":\"/dev/cu.usbmodem1101\"}");
        startStream("/Users/op/jobs/dr_ryan_complete_T02_then_T21.nc", 26781, 10.0);
        TriggerEngine.Notification n = one(feed("event", 10 + 61 * 60 + 12, "{\"type\":\"stream\",\"event\":\"complete\"}"));
        assertEquals(Triggers.STREAM_FINISHED, n.trigger());
        assertEquals(Triggers.INFO, n.level());
        assertEquals("✔ Job finished: dr_ryan_complete_T02_then_T21.nc — 61 min, 26 781 lines", n.text());
    }

    @Test
    public void canceledStreamIsNotAFinish() {
        startStream("/x/a.nc", 10, 1);
        assertTrue(feed("event", 2, "{\"type\":\"stream\",\"event\":\"canceled\"}").isEmpty());
        assertTrue(feed("event", 3, "{\"type\":\"stream\",\"event\":\"complete\"}").isEmpty());
    }

    @Test
    public void streamErrorNamesTheFailingLineAndResponse() {
        startStream("/x/a.nc", 500, 1);
        feed("tx", 2, "{\"line\":\"G1 X10\",\"cmd_id\":\"u-40\",\"file_line\":41}");
        feed("tx", 2, "{\"line\":\"G5 X1\",\"cmd_id\":\"u-41\",\"file_line\":42}");
        feed("tx", 2, "{\"line\":\"G1 X12\",\"cmd_id\":\"u-42\",\"file_line\":43}");
        feed("command", 2.1, "{\"id\":\"u-41\",\"origin\":\"ugs\",\"line\":\"G5 X1\",\"state\":\"error\",\"error_code\":20,"
                + "\"response\":[\"error:20\"]}");
        TriggerEngine.Notification n = one(feed("event", 2.2, "{\"type\":\"stream\",\"event\":\"paused_on_error\"}"));
        assertEquals(Triggers.STREAM_ERROR, n.trigger());
        assertEquals(Triggers.ALERT, n.level());
        assertEquals("⛔ Stream stopped on error: a.nc line 42 — error:20 on G5 X1. Waiting in UGS.", n.text());
    }

    @Test
    public void alarmWithCodeDuringStream() {
        startStream("/x/a.nc", 500, 1);
        feed("tx", 2, "{\"line\":\"G1 X10\",\"cmd_id\":\"u-1\",\"file_line\":1234}");
        TriggerEngine.Notification n = one(feed("event", 3, "{\"type\":\"alarm\",\"code\":1,\"line\":\"ALARM:1\"}"));
        assertEquals("⚠ ALARM 1: hard limit — a.nc at line 1 234", n.text());
        assertEquals(Triggers.ALERT, n.level());
        // UGS's coarse AlarmEvent for the same alarm adds nothing
        assertTrue(feed("event", 3, "{\"type\":\"alarm\",\"ugs_alarm\":\"hard_limit\"}").isEmpty());
        advance(5);
        assertTrue(engine.tick().isEmpty());
    }

    @Test
    public void probeAlarmOutsideAStream() {
        assertEquals("⚠ ALARM 4: probe fail (probe already triggered)",
                one(feed("event", 3, "{\"type\":\"alarm\",\"code\":4,\"line\":\"ALARM:4\"}")).text());
        assertEquals("⚠ ALARM 42", one(feed("event", 3, "{\"type\":\"alarm\",\"code\":42}")).text());
    }

    @Test
    public void ugsAlarmWithoutWireCodeIsReportedAfterGrace() {
        assertTrue(feed("event", 3, "{\"type\":\"alarm\",\"ugs_alarm\":\"hard_limit\"}").isEmpty());
        advance(1);
        assertTrue(engine.tick().isEmpty());
        advance(1.5);
        TriggerEngine.Notification n = one(engine.tick());
        assertEquals("⚠ ALARM: hard limit", n.text());
        assertTrue(engine.tick().isEmpty());
    }

    @Test
    public void feedHoldLongerThanConfiguredSeconds() {
        startStream("/x/a.nc", 500, 1);
        feed("tx", 2, "{\"line\":\"G1 X10\",\"cmd_id\":\"u-1\",\"file_line\":77}");
        feed("event", 3, "{\"type\":\"controller_state\",\"state\":\"hold\",\"previous\":\"run\"}");
        advance(59);
        assertTrue(engine.tick().isEmpty());
        advance(1);
        TriggerEngine.Notification n = one(engine.tick());
        assertEquals(Triggers.FEED_HOLD, n.trigger());
        assertEquals("⏸ Feed hold for 60 s — a.nc at line 77", n.text());
        advance(60);
        assertTrue("once per hold", engine.tick().isEmpty());
        // resume, hold again briefly: nothing
        feed("event", 4, "{\"type\":\"controller_state\",\"state\":\"run\",\"previous\":\"hold\"}");
        feed("event", 5, "{\"type\":\"controller_state\",\"state\":\"hold\",\"previous\":\"run\"}");
        advance(10);
        feed("event", 6, "{\"type\":\"controller_state\",\"state\":\"run\",\"previous\":\"hold\"}");
        advance(100);
        assertTrue(engine.tick().isEmpty());
    }

    @Test
    public void doorAndHoldThresholdFromConfig() {
        NotifyConfig.Builder b = NotifyConfig.defaults().toBuilder();
        b.feedHoldSeconds = 5;
        config.set(b.build());
        feed("event", 3, "{\"type\":\"controller_state\",\"state\":\"door\",\"previous\":\"run\"}");
        advance(5);
        assertEquals("🚪 Safety door open for 5 s", one(engine.tick()).text());
    }

    @Test
    public void toolChangeWaitingNamesTheToolFromTheHeaders() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("two_tools_T02_T21.nc");
        Files.writeString(file, TWO_TOOLS);
        startStream(file.toString(), 16, 1);
        feed("event", 2, "{\"type\":\"interceptor\",\"state\":\"pending\",\"previous\":\"inactive\","
                + "\"interceptor\":\"ToolChangeInterceptor\",\"trigger\":\"T21 M6\",\"file_line\":11}");
        TriggerEngine.Notification n = one(feed("event", 3, "{\"type\":\"interceptor\",\"state\":\"waiting_for_user\","
                + "\"previous\":\"running\",\"interceptor\":\"ToolChangeInterceptor\",\"trigger\":\"T21 M6\","
                + "\"file_line\":11,\"step\":\"toolChange.changeTool\",\"options\":[\"continue\",\"abort\"]}"));
        assertEquals(Triggers.TOOL_CHANGE_WAITING, n.trigger());
        assertEquals(Triggers.WARN, n.level());
        assertEquals("🔧 Tool change: install T21 2-flute upcut spiral 1/8 in, Whiteside RU1600 "
                + "(two_tools_T02_T21.nc, line 11). Confirm in UGS.", n.text());
        // the second prompt of the same tool change is not repeated
        assertTrue(feed("event", 4, "{\"type\":\"interceptor\",\"state\":\"waiting_for_user\",\"interceptor\":"
                + "\"ToolChangeInterceptor\",\"trigger\":\"T21 M6\",\"step\":\"toolChange.continue\"}").isEmpty());
    }

    @Test
    public void toolChangeWithoutHeadersFallsBackToTheTWord() {
        startStream("/does/not/exist.nc", 100, 1);
        assertEquals("🔧 Tool change: install T5 (exist.nc, line 9). Confirm in UGS.",
                one(feed("event", 3, "{\"type\":\"interceptor\",\"state\":\"waiting_for_user\",\"interceptor\":"
                        + "\"ToolChangeInterceptor\",\"trigger\":\"M6 T5\",\"file_line\":9,\"step\":\"toolChange.changeTool\"}")).text());
    }

    @Test
    public void bitsetterInterceptorPromptsNotifyToo() {
        startStream("/does/not/exist.nc", 100, 1);
        assertEquals("🔧 Tool change: install T21 (exist.nc, line 9). Confirm in UGS.",
                one(feed("event", 3, "{\"type\":\"interceptor\",\"state\":\"waiting_for_user\",\"interceptor\":"
                        + "\"BitSetterInterceptor\",\"trigger\":\"T21 M6\",\"file_line\":9,\"step\":\"bridge.toolChange.change\"}")).text());
        // the probe-pin re-prompt is part of the same change
        assertTrue(feed("event", 4, "{\"type\":\"interceptor\",\"state\":\"waiting_for_user\",\"interceptor\":"
                + "\"BitSetterInterceptor\",\"trigger\":\"T21 M6\",\"step\":\"bridge.toolChange.probePin\"}").isEmpty());
        TriggerEngine.Notification n = one(feed("event", 5, "{\"type\":\"interceptor\",\"state\":\"waiting_for_user\","
                + "\"interceptor\":\"BitSetterInterceptor\",\"trigger\":\"T21 M6\",\"file_line\":9,"
                + "\"step\":\"bridge.toolChange.noReference\"}"));
        assertEquals(Triggers.ALERT, n.level());
        assertTrue(n.text(), n.text().contains("no BitSetter reference"));
    }

    @Test
    public void toolChangeFailedIsAnAlert() {
        TriggerEngine.Notification n = one(feed("event", 3, "{\"type\":\"interceptor\",\"state\":\"failed\","
                + "\"interceptor\":\"ToolChangeInterceptor\",\"trigger\":\"M6\"}"));
        assertEquals(Triggers.ALERT, n.level());
        assertEquals("🔧 Tool change routine failed; stream left paused", n.text());
    }

    @Test
    public void connectionLostOnlyWhenUnexpected() {
        feed("session", 1, "{\"event\":\"connect\",\"port\":\"127.0.0.1:2323\"}");
        assertTrue(feed("session", 2, "{\"event\":\"disconnect\"}").isEmpty());
        feed("session", 3, "{\"event\":\"connect\",\"port\":\"127.0.0.1:2323\"}");
        startStream("/x/a.nc", 500, 4);
        feed("tx", 5, "{\"line\":\"G1 X10\",\"cmd_id\":\"u-1\",\"file_line\":300}");
        TriggerEngine.Notification n = one(feed("session", 6, "{\"event\":\"disconnect\",\"reason\":\"connection_closed\"}"));
        assertEquals(Triggers.CONNECTION_LOST, n.trigger());
        assertEquals("🔌 Connection to the controller lost (127.0.0.1:2323) — during a.nc at line 300", n.text());
    }

    @Test
    public void silentControllerIsAConnectionLostAlert() {
        feed("session", 1, "{\"event\":\"connect\",\"port\":\"cu.usbmodem11201\"}");
        TriggerEngine.Notification n = one(feed("event", 2, "{\"type\":\"controller_silent\",\"age_s\":10.4}"));
        assertEquals(Triggers.CONNECTION_LOST, n.trigger());
        assertEquals(Triggers.ALERT, n.level());
        assertEquals("🔇 The controller stopped answering (cu.usbmodem11201): no status for 10 s, it may be frozen"
                + " or disconnected", n.text());
        assertTrue(feed("event", 3, "{\"type\":\"controller_responding\"}").isEmpty());
    }

    @Test
    public void progressEveryNPercentOnlyWhenConfigured() {
        startStream("/x/a.nc", 1000, 0);
        for (int i = 1; i <= 1000; i++) {
            assertTrue(feed("tx", i, "{\"cmd_id\":\"u-" + i + "\",\"file_line\":" + i + "}").isEmpty());
        }
        NotifyConfig.Builder b = NotifyConfig.defaults().toBuilder();
        b.progressEveryPercent = 25;
        config.set(b.build());
        startStream("/x/a.nc", 1000, 0);
        List<String> texts = new java.util.ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            feed("tx", i * 1.2, "{\"cmd_id\":\"u-" + i + "\",\"file_line\":" + i + "}").forEach(n -> texts.add(n.text()));
        }
        assertEquals(List.of(
                "▶ 25 % — a.nc, line 250 of 1 000, 5 min elapsed, ~15 min left",
                "▶ 50 % — a.nc, line 500 of 1 000, 10 min elapsed, ~10 min left",
                "▶ 75 % — a.nc, line 750 of 1 000, 15 min elapsed, ~5 min left"), texts);
    }

    @Test
    public void durationsAndCounts() {
        assertEquals("45 s", Messages.duration(45));
        assertEquals("60 s", Messages.duration(60));
        assertEquals("2 min", Messages.duration(120));
        assertEquals("99 min", Messages.duration(99 * 60));
        assertEquals("2 h 05 min", Messages.duration(125 * 60));
        assertEquals("26 781", Messages.count(26781));
        assertEquals("1 234 567", Messages.count(1234567));
    }
}
