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
package com.willwinder.ugs.nbp.bridge.capture;

import com.google.gson.JsonObject;
import com.willwinder.ugs.nbp.bridge.log.LogFiles;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;
import com.willwinder.universalgcodesender.IController;
import com.willwinder.universalgcodesender.communicator.ICommunicator;
import com.willwinder.universalgcodesender.connection.ConnectionDriver;
import com.willwinder.universalgcodesender.firmware.FirmwareSetting;
import com.willwinder.universalgcodesender.firmware.IFirmwareSettings;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.ControllerStatus;
import com.willwinder.universalgcodesender.listeners.MessageType;
import com.willwinder.universalgcodesender.model.Alarm;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils;
import com.willwinder.universalgcodesender.model.events.AlarmEvent;
import com.willwinder.universalgcodesender.model.events.CommandEvent;
import com.willwinder.universalgcodesender.model.events.CommandEventType;
import com.willwinder.universalgcodesender.model.events.ControllerStateEvent;
import com.willwinder.universalgcodesender.model.events.ControllerStatusEvent;
import com.willwinder.universalgcodesender.model.events.FileState;
import com.willwinder.universalgcodesender.model.events.FileStateEvent;
import com.willwinder.universalgcodesender.model.events.FirmwareSettingEvent;
import com.willwinder.universalgcodesender.model.events.ProbeEvent;
import com.willwinder.universalgcodesender.model.events.SettingChangedEvent;
import com.willwinder.universalgcodesender.model.events.StreamEvent;
import com.willwinder.universalgcodesender.model.events.StreamEventType;
import com.willwinder.universalgcodesender.services.interceptor.CommandInterceptor;
import com.willwinder.universalgcodesender.services.interceptor.InterceptorPrompt;
import com.willwinder.universalgcodesender.services.interceptor.InterceptorState;
import com.willwinder.universalgcodesender.services.interceptor.InterceptorStateEvent;
import com.willwinder.universalgcodesender.services.interceptor.UserResponse;
import com.willwinder.universalgcodesender.types.GcodeCommand;
import com.willwinder.universalgcodesender.utils.Settings;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CaptureTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    final AtomicLong nanos = new AtomicLong();
    Path home;
    SessionLog log;
    Capture capture;
    BackendAPI backend;
    IController controller;
    ICommunicator comm;
    Settings settings;

    @Before
    public void setUp() throws Exception {
        home = tmp.newFolder("bridge-home").toPath();
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T22:30:01.250Z"), ZoneOffset.ofHours(-5));
        log = LogFiles.newLog(home, clock, nanos::get, 10_000);
        log.start();
        capture = new Capture(log, new CommandOrigins(), "test", nanos::get);

        backend = mock(BackendAPI.class);
        controller = mock(IController.class);
        comm = mock(ICommunicator.class);
        settings = mock(Settings.class);
        IFirmwareSettings fw = mock(IFirmwareSettings.class);
        when(backend.getController()).thenReturn(controller);
        when(backend.getSettings()).thenReturn(settings);
        when(controller.getCommunicator()).thenReturn(comm);
        when(controller.getFirmwareSettings()).thenReturn(fw);
        when(fw.getReportingUnits()).thenReturn(UnitUtils.Units.MM);
        when(settings.getConnectionDriver()).thenReturn(ConnectionDriver.TCP);
        when(settings.getPort()).thenReturn("127.0.0.1");
        when(settings.getPortRate()).thenReturn("2323");
        when(settings.getFirmwareVersion()).thenReturn("GRBL");
        capture.attach(backend);
    }

    @After
    public void tearDown() {
        capture.detach();
        log.close();
    }

    List<JsonObject> records() throws InterruptedException {
        assertTrue(log.awaitWritten(3000));
        return LogFiles.readAll(home);
    }

    static List<JsonObject> ofKind(List<JsonObject> recs, String kind) {
        return recs.stream().filter(r -> r.get("kind").getAsString().equals(kind)).toList();
    }

    static JsonObject data(JsonObject r) {
        return r.getAsJsonObject("data");
    }

    void connect() {
        capture.UGSEvent(new ControllerStateEvent(ControllerState.CONNECTING, ControllerState.DISCONNECTED));
    }

    void idle() {
        capture.UGSEvent(new ControllerStateEvent(ControllerState.IDLE, ControllerState.CONNECTING));
    }

    static GcodeCommand fileLine(String line, int index) {
        return new GcodeCommand(line, line, "", index);
    }

    @Test
    public void connectOpensSessionAndAttachesToCommunicator() throws Exception {
        connect();
        verify(comm).addListener(capture);
        List<JsonObject> recs = records();
        JsonObject s = ofKind(recs, "session").get(0);
        assertEquals("connect", data(s).get("event").getAsString());
        assertEquals("127.0.0.1:2323", data(s).get("port").getAsString());
        assertEquals("tcp", data(s).get("driver").getAsString());
        assertEquals("GRBL", data(s).get("firmware").getAsString());
        assertEquals("20261115-173001-tcp_127.0.0.1_2323", s.get("session").getAsString());
        assertTrue(Files.exists(home.resolve("logs/2026-11-15/173001-tcp_127.0.0.1_2323/session.jsonl")));
        JsonObject ev = ofKind(recs, "event").get(0);
        assertEquals("controller_state", data(ev).get("type").getAsString());
        assertEquals("connecting", data(ev).get("state").getAsString());
        assertEquals("ugs", ev.get("src").getAsString());
    }

    @Test
    public void reInitializingDoesNotStartANewSessionButDisconnectDoes() throws Exception {
        connect();
        idle();
        capture.UGSEvent(new ControllerStateEvent(ControllerState.CONNECTING, ControllerState.IDLE)); // soft reset
        capture.UGSEvent(new ControllerStateEvent(ControllerState.DISCONNECTED, ControllerState.CONNECTING));
        verify(comm).removeListener(capture);
        List<JsonObject> sessions = ofKind(records(), "session");
        assertEquals(List.of("connect", "disconnect"),
                sessions.stream().map(r -> data(r).get("event").getAsString()).toList());
        connect();
        assertEquals(3, ofKind(records(), "session").size());
    }

    @Test
    public void connectionClosedEndsSessionOnce() throws Exception {
        connect();
        capture.onConnectionClosed();
        capture.UGSEvent(new ControllerStateEvent(ControllerState.DISCONNECTED, ControllerState.IDLE));
        List<JsonObject> sessions = ofKind(records(), "session");
        assertEquals(2, sessions.size());
        assertEquals("connection_closed", data(sessions.get(1)).get("reason").getAsString());
        assertEquals("controller", sessions.get(1).get("src").getAsString());
    }

    @Test
    public void operatorCommandIsTracedThroughTxRxAndCommand() throws Exception {
        connect();
        idle();
        GcodeCommand cmd = new GcodeCommand("$G");
        capture.commandSent(cmd);
        capture.rawResponseListener("[GC:G0 G54 G17 G21 G90 G94 M5 M9 T0 F0 S0]");
        capture.rawResponseListener("ok");
        cmd.appendResponse("[GC:G0 G54 G17 G21 G90 G94 M5 M9 T0 F0 S0]");
        cmd.appendResponse("ok");
        capture.UGSEvent(new CommandEvent(CommandEventType.COMMAND_COMPLETE, cmd));
        capture.rawResponseListener("[MSG:unsolicited]");

        List<JsonObject> recs = records();
        String id = "u-" + cmd.getId();
        JsonObject tx = ofKind(recs, "tx").get(0);
        assertEquals("$G", data(tx).get("line").getAsString());
        assertEquals(id, data(tx).get("cmd_id").getAsString());
        assertEquals("operator", tx.get("src").getAsString());
        assertFalse(data(tx).has("file_line"));

        List<JsonObject> rx = ofKind(recs, "rx");
        assertEquals(3, rx.size());
        assertEquals(id, data(rx.get(0)).get("cmd_id").getAsString());
        assertEquals("operator", rx.get(1).get("src").getAsString());
        assertEquals(id, data(rx.get(1)).get("cmd_id").getAsString());
        assertFalse(data(rx.get(2)).has("cmd_id"));
        assertEquals("controller", rx.get(2).get("src").getAsString());

        List<JsonObject> cmds = ofKind(recs, "command");
        assertEquals(List.of("sent", "ok"), cmds.stream().map(r -> data(r).get("state").getAsString()).toList());
        JsonObject done = data(cmds.get(1));
        assertEquals(id, done.get("id").getAsString());
        assertEquals("operator", done.get("origin").getAsString());
        assertEquals(2, done.getAsJsonArray("response").size());
    }

    @Test
    public void streamedLinesAreUgsWithFileLineAndOnlyFailuresGetCommandRecords() throws Exception {
        connect();
        idle();
        GcodeCommand a = fileLine("G0 X1", 0);
        GcodeCommand b = fileLine("G1 X2 F100", 1);
        capture.commandSent(a);
        capture.commandSent(b);
        capture.rawResponseListener("ok");
        capture.rawResponseListener("error:22");
        a.appendResponse("ok");
        b.appendResponse("error:22");
        capture.UGSEvent(new CommandEvent(CommandEventType.COMMAND_COMPLETE, a));
        capture.UGSEvent(new CommandEvent(CommandEventType.COMMAND_COMPLETE, b));
        capture.communicatorPausedOnError();

        List<JsonObject> recs = records();
        List<JsonObject> tx = ofKind(recs, "tx");
        assertEquals(1, data(tx.get(0)).get("file_line").getAsInt());
        assertEquals(2, data(tx.get(1)).get("file_line").getAsInt());
        assertEquals("ugs", tx.get(0).get("src").getAsString());
        List<JsonObject> rx = ofKind(recs, "rx");
        assertEquals("u-" + a.getId(), data(rx.get(0)).get("cmd_id").getAsString());
        assertEquals("u-" + b.getId(), data(rx.get(1)).get("cmd_id").getAsString());
        List<JsonObject> cmds = ofKind(recs, "command");
        assertEquals(1, cmds.size());
        assertEquals("error", data(cmds.get(0)).get("state").getAsString());
        assertEquals(22, data(cmds.get(0)).get("error_code").getAsInt());
        assertTrue(ofKind(recs, "event").stream().anyMatch(r -> "paused_on_error".equals(
                data(r).has("event") ? data(r).get("event").getAsString() : null)));
    }

    @Test
    public void realtimeStatusPollsAreCountedNotLoggedAndReportsAreNotRx() throws Exception {
        connect();
        idle();
        capture.realtimeByteSent((byte) '?');
        capture.rawResponseListener("<Idle|MPos:0.000,0.000,0.000|FS:0,0|WCO:0.000,0.000,0.000>");
        capture.realtimeByteSent((byte) '?');
        capture.rawResponseListener("<Idle|MPos:0.000,0.000,0.000|FS:0,0>");
        capture.rawResponseListener("<Idle|MPos:0.000,0.000,0.000|FS:0,0>");
        capture.onMessage(MessageType.VERBOSE, "<Idle|MPos:0.000,0.000,0.000|FS:0,0>\n");
        nanos.addAndGet(1_100_000_000L);
        capture.tick();
        capture.tick();
        capture.rawResponseListener("<Run|MPos:1.000,0.000,0.000|FS:500,0>");

        List<JsonObject> recs = records();
        assertTrue(ofKind(recs, "tx").isEmpty());
        assertTrue(ofKind(recs, "rx").isEmpty());
        assertTrue(ofKind(recs, "message").isEmpty());
        assertEquals(2, capture.statusPolls());
        List<JsonObject> st = ofKind(recs, "status");
        assertEquals(3, st.size());
        assertFalse(data(st.get(0)).has("heartbeat"));
        assertTrue(data(st.get(1)).get("heartbeat").getAsBoolean());
        assertEquals("Run", data(st.get(2)).get("state").getAsString());
        assertEquals("mm", data(st.get(2)).get("units").getAsString());
        assertEquals("controller", st.get(0).get("src").getAsString());
    }

    /**
     * A-2: UGS's GetStatusCommand sends "?" as a whole line at connect; GRBL answers with a report and "ok".
     * The old capture skipped the line but counted its "ok" as an early answer, which made every later
     * answer whose command was already done (normal with UGS's async event dispatch) shift by one.
     * Event order as UGS delivers it with patch P6 (commandSent always before the answer), while the
     * command objects are already complete when the capture sees commandSent.
     */
    @Test
    public void wholeLineStatusQueryDoesNotShiftLaterAnswers() throws Exception {
        connect();
        List<GcodeCommand> sent = new ArrayList<>();
        for (int round = 0; round < 3; round++) { // UGS retries the query until a report arrives
            GcodeCommand q = new GcodeCommand("?");
            q.appendResponse("<Alarm|MPos:0.000,0.000,0.000|FS:0,0>");
            q.appendResponse("ok");
            capture.commandSent(q);
            capture.rawResponseListener("<Alarm|MPos:0.000,0.000,0.000|FS:0,0>");
            capture.rawResponseListener("ok");
            sent.add(q);
        }
        idle();
        for (int i = 0; i < 20; i++) {
            GcodeCommand c = fileLine("G1 X" + i, i);
            c.appendResponse("ok"); // already done when its commandSent event is processed
            capture.commandSent(c);
            sent.add(c);
        }
        for (int i = 0; i < 20; i++) {
            capture.rawResponseListener("ok");
        }

        List<JsonObject> recs = records();
        List<JsonObject> tx = ofKind(recs, "tx");
        List<JsonObject> rx = ofKind(recs, "rx");
        assertEquals(sent.size(), tx.size());
        assertEquals("?", data(tx.get(0)).get("line").getAsString());
        assertEquals(sent.size(), rx.size());
        for (int i = 0; i < sent.size(); i++) {
            assertEquals("answer " + i, "u-" + sent.get(i).getId(), data(rx.get(i)).get("cmd_id").getAsString());
        }
    }

    @Test
    public void answerWithNothingWaitingIsUnsolicitedAndDoesNotShiftLaterAnswers() throws Exception {
        connect();
        idle();
        capture.rawResponseListener("ok"); // answers something sent before the bridge attached
        GcodeCommand a = new GcodeCommand("G4 P0");
        a.appendResponse("ok");
        capture.commandSent(a);
        GcodeCommand b = new GcodeCommand("$#");
        capture.commandSent(b);
        capture.rawResponseListener("ok");
        capture.rawResponseListener("ok");
        List<JsonObject> rx = ofKind(records(), "rx");
        assertFalse(data(rx.get(0)).has("cmd_id"));
        assertEquals("controller", rx.get(0).get("src").getAsString());
        assertEquals("u-" + a.getId(), data(rx.get(1)).get("cmd_id").getAsString());
        assertEquals("u-" + b.getId(), data(rx.get(2)).get("cmd_id").getAsString());
    }

    @Test
    public void statusEventIsUsedOnlyWithoutRawReports() throws Exception {
        connect();
        ControllerStatus s = new ControllerStatus(ControllerState.IDLE, new Position(1, 2, 3, UnitUtils.Units.MM),
                new Position(0, 0, 0, UnitUtils.Units.MM));
        capture.UGSEvent(new ControllerStatusEvent(s, ControllerStatus.EMPTY_CONTROLLER_STATUS));
        capture.rawResponseListener("<Idle|MPos:5.000,0.000,0.000|FS:0,0>");
        ControllerStatus s2 = new ControllerStatus(ControllerState.RUN, new Position(1, 2, 3, UnitUtils.Units.MM),
                new Position(0, 0, 0, UnitUtils.Units.MM));
        capture.UGSEvent(new ControllerStatusEvent(s2, s));
        List<JsonObject> st = ofKind(records(), "status");
        assertEquals(2, st.size());
        assertEquals("", data(st.get(0)).get("raw").getAsString());
        assertEquals("<Idle|MPos:5.000,0.000,0.000|FS:0,0>", data(st.get(1)).get("raw").getAsString());
    }

    @Test
    public void welcomeAfterResetClearsPendingCommands() throws Exception {
        connect();
        idle();
        capture.commandSent(new GcodeCommand("G0 X10"));
        capture.rawResponseListener("Grbl 1.1h ['$' for help]");
        capture.rawResponseListener("ok");
        List<JsonObject> rx = ofKind(records(), "rx");
        assertFalse(data(rx.get(0)).has("cmd_id"));
        assertFalse(data(rx.get(1)).has("cmd_id"));
    }

    @Test
    public void initializationAndInterceptorCommandsAreUgs() throws Exception {
        connect(); // still CONNECTING
        capture.commandSent(new GcodeCommand("$I"));
        idle();
        capture.UGSEvent(new InterceptorStateEvent(InterceptorState.RUNNING, InterceptorState.PENDING,
                mock(CommandInterceptor.class), fileLine("M6 T2", 9), null));
        capture.commandSent(new GcodeCommand("G53 G0 Z-5"));
        capture.UGSEvent(new InterceptorStateEvent(InterceptorState.WAITING_FOR_USER, InterceptorState.RUNNING,
                null, null, new InterceptorPrompt("confirm_tool", List.of(UserResponse.CONTINUE))));
        capture.UGSEvent(new InterceptorStateEvent(InterceptorState.INACTIVE, InterceptorState.RESUMING, null, null, null));
        capture.commandSent(new GcodeCommand("G0 X0"));

        List<JsonObject> recs = records();
        assertEquals(List.of("ugs", "ugs", "operator"),
                ofKind(recs, "tx").stream().map(r -> r.get("src").getAsString()).toList());
        List<JsonObject> ic = ofKind(recs, "event").stream()
                .filter(r -> data(r).get("type").getAsString().equals("interceptor")).toList();
        assertEquals(3, ic.size());
        assertEquals("M6 T2", data(ic.get(0)).get("trigger").getAsString());
        assertEquals(10, data(ic.get(0)).get("file_line").getAsInt());
        assertEquals("confirm_tool", data(ic.get(1)).get("step").getAsString());
        assertEquals("continue", data(ic.get(1)).getAsJsonArray("options").get(0).getAsString().toLowerCase());
    }

    @Test
    public void claudeCommandsUseRegisteredIdAndSource() throws Exception {
        connect();
        idle();
        GcodeCommand cmd = new GcodeCommand("G0 Z5");
        capture.origins().register(cmd, "c-7-0", "claude", "lift");
        capture.commandSent(cmd);
        capture.rawResponseListener("ok");
        cmd.appendResponse("ok");
        capture.UGSEvent(new CommandEvent(CommandEventType.COMMAND_COMPLETE, cmd));
        List<JsonObject> recs = records();
        JsonObject tx = ofKind(recs, "tx").get(0);
        assertEquals("claude", tx.get("src").getAsString());
        assertEquals("c-7-0", data(tx).get("cmd_id").getAsString());
        assertEquals("claude", ofKind(recs, "rx").get(0).get("src").getAsString());
        JsonObject done = data(ofKind(recs, "command").get(1));
        assertEquals("claude", done.get("origin").getAsString());
        assertEquals("lift", done.get("tag").getAsString());
        assertEquals(0, capture.origins().size());
    }

    @Test
    public void realtimeBytesAreTxWithHexAndAttribution() throws Exception {
        connect();
        idle();
        capture.attributeRealtime((byte) '!', "claude", "c-1-0");
        capture.realtimeByteSent((byte) '!');
        capture.realtimeByteSent((byte) '!'); // a second hold is the operator's again
        capture.realtimeByteSent((byte) 0x85);
        capture.attributeRealtime((byte) 0x18, "claude", "c-2-0");
        nanos.addAndGet(3_000_000_000L); // expired: not attributed any more
        capture.realtimeByteSent((byte) 0x18);
        List<JsonObject> tx = ofKind(records(), "tx");
        assertEquals(4, tx.size());
        assertEquals("!", data(tx.get(0)).get("line").getAsString());
        assertEquals("0x21", data(tx.get(0)).get("realtime").getAsString());
        assertEquals("claude", tx.get(0).get("src").getAsString());
        assertEquals("c-1-0", data(tx.get(0)).get("cmd_id").getAsString());
        assertEquals("operator", tx.get(1).get("src").getAsString());
        assertFalse(data(tx.get(1)).has("cmd_id"));
        assertEquals("0x85", data(tx.get(2)).get("line").getAsString());
        assertEquals("0x85", data(tx.get(2)).get("realtime").getAsString());
        assertEquals("0x18", data(tx.get(3)).get("line").getAsString());
        assertEquals("operator", tx.get(3).get("src").getAsString());
    }

    @Test
    public void consoleEchoesAreDroppedOtherMessagesKept() throws Exception {
        connect();
        capture.onMessage(MessageType.INFO, ">>> G0 X10\n");
        capture.onMessage(MessageType.VERBOSE, ">>> $G\n");
        capture.onMessage(MessageType.INFO, "[claude] >>> G0 Z5\n");
        capture.onMessage(MessageType.INFO, "ok\n");
        capture.onMessage(MessageType.VERBOSE, "ok\n");
        capture.onMessage(MessageType.INFO, "[PRB:-4.000,-449.000,-65.000:1]\nok\n");
        capture.onMessage(MessageType.INFO, "*** Connected to 127.0.0.1 @ 2323 baud\n");
        capture.onMessage(MessageType.ERROR, "An error occurred while processing: G0 X1000 (error:15)\n");
        List<String> texts = ofKind(records(), "message").stream().map(r -> data(r).get("text").getAsString()).toList();
        assertEquals(List.of("[PRB:-4.000,-449.000,-65.000:1]\nok", "*** Connected to 127.0.0.1 @ 2323 baud",
                "An error occurred while processing: G0 X1000 (error:15)"), texts);
    }

    @Test
    public void alarmsProbeSettingsAndMessages() throws Exception {
        connect();
        idle();
        capture.rawResponseListener("ALARM:1");
        capture.UGSEvent(new AlarmEvent(Alarm.HARD_LIMIT));
        capture.UGSEvent(new ProbeEvent(new Position(-10, -20, -30.5, UnitUtils.Units.MM)));
        capture.UGSEvent(new FirmwareSettingEvent(new FirmwareSetting("$13", "0")));
        capture.UGSEvent(new SettingChangedEvent());
        capture.onMessage(MessageType.ERROR, "An error\n");

        List<JsonObject> recs = records();
        List<JsonObject> ev = ofKind(recs, "event");
        JsonObject alarm = ev.stream().filter(r -> data(r).get("type").getAsString().equals("alarm")
                && data(r).has("code")).findFirst().orElseThrow();
        assertEquals(1, data(alarm).get("code").getAsInt());
        assertTrue(ev.stream().anyMatch(r -> data(r).has("ugs_alarm")
                && data(r).get("ugs_alarm").getAsString().equals("hard_limit")));
        JsonObject probe = ev.stream().filter(r -> data(r).get("type").getAsString().equals("probe")).findFirst().orElseThrow();
        assertEquals(-30.5, data(probe).getAsJsonArray("position").get(2).getAsDouble(), 1e-9);
        assertEquals(2, ev.stream().filter(r -> data(r).get("type").getAsString().equals("setting")).count());
        JsonObject msg = ofKind(recs, "message").get(0);
        assertEquals("ERROR", data(msg).get("type").getAsString());
        assertEquals("An error", data(msg).get("text").getAsString());
    }

    @Test
    public void fileLoadAndStreamAreHashedAndCopiedOnce() throws Exception {
        Path job = tmp.newFile("job.nc").toPath();
        Files.writeString(job, "G21\nG0 X1\nM2\n");
        File f = job.toFile();
        when(backend.getGcodeFile()).thenReturn(f);
        connect();
        idle();
        capture.UGSEvent(new FileStateEvent(FileState.FILE_LOADED));
        capture.UGSEvent(new StreamEvent(StreamEventType.STREAM_STARTED));
        capture.UGSEvent(new StreamEvent(StreamEventType.STREAM_COMPLETE));
        capture.UGSEvent(new FileStateEvent(FileState.FILE_STREAM_COMPLETE));
        capture.UGSEvent(new StreamEvent(StreamEventType.STREAM_STARTED));
        capture.UGSEvent(new StreamEvent(StreamEventType.STREAM_CANCELED));

        List<JsonObject> recs = records();
        List<JsonObject> files = ofKind(recs, "file");
        assertEquals(List.of("loaded", "stream_start", "stream_end", "stream_start", "stream_end"),
                files.stream().map(r -> data(r).get("action").getAsString()).toList());
        String sha = data(files.get(0)).get("sha256").getAsString();
        assertEquals(3, data(files.get(0)).get("lines").getAsInt());
        assertTrue(files.stream().allMatch(r -> data(r).get("sha256").getAsString().equals(sha)));
        String copied = data(files.get(1)).get("copied_to").getAsString();
        assertEquals("files/" + sha + ".nc", copied);
        Path copy = log.sessionDir().resolve(copied);
        assertEquals(Files.readString(job), Files.readString(copy));
        try (var s = Files.list(copy.getParent())) {
            assertEquals(1, s.count());
        }
        assertEquals("complete", data(files.get(2)).get("result").getAsString());
        assertEquals("canceled", data(files.get(4)).get("result").getAsString());
        assertEquals("ugs", files.get(2).get("src").getAsString());
        assertEquals("operator", files.get(1).get("src").getAsString());
        assertEquals(List.of("started", "complete", "started", "canceled"),
                ofKind(recs, "event").stream().filter(r -> data(r).get("type").getAsString().equals("stream"))
                        .map(r -> data(r).get("event").getAsString()).collect(Collectors.toList()));
    }

    @Test
    public void streamCanBeAttributedToClaude() throws Exception {
        Path job = tmp.newFile("job.nc").toPath();
        Files.writeString(job, "G0 X1\n");
        when(backend.getGcodeFile()).thenReturn(job.toFile());
        connect();
        capture.attributeNextStream("claude");
        capture.UGSEvent(new StreamEvent(StreamEventType.STREAM_STARTED));
        assertEquals("claude", ofKind(records(), "file").get(0).get("src").getAsString());
    }

    @Test
    public void attachWhileConnectedStartsSession() throws Exception {
        Capture other = new Capture(log, new CommandOrigins(), "test", nanos::get);
        BackendAPI b = mock(BackendAPI.class);
        when(b.isConnected()).thenReturn(true);
        when(b.getSettings()).thenReturn(settings);
        when(b.getController()).thenReturn(controller);
        other.attach(b);
        assertNotNull(log.session());
        verify(comm).addListener(other);
        other.detach();
        verify(comm).removeListener(other);
    }
}
