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
package com.willwinder.ugs.nbp.bridge;

import com.google.gson.JsonObject;
import com.willwinder.ugs.nbp.bridge.capture.Capture;
import com.willwinder.ugs.nbp.bridge.log.LogFiles;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;
import com.willwinder.universalgcodesender.IController;
import com.willwinder.universalgcodesender.communicator.ICommunicator;
import com.willwinder.universalgcodesender.connection.ConnectionDriver;
import com.willwinder.universalgcodesender.firmware.FirmwareSetting;
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
import com.willwinder.universalgcodesender.services.interceptor.InterceptorPrompt;
import com.willwinder.universalgcodesender.services.interceptor.InterceptorState;
import com.willwinder.universalgcodesender.services.interceptor.InterceptorStateEvent;
import com.willwinder.universalgcodesender.services.interceptor.UserResponse;
import com.willwinder.universalgcodesender.types.GcodeCommand;
import com.willwinder.universalgcodesender.utils.Settings;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives the bridge start, lock changes and a full mocked session through {@link BridgeService}, then
 * validates every emitted record against bridge/schema/log-record.schema.json with
 * bridge/scripts/validate_log.py (skipped if {@code uv} is not available).
 */
public class BridgeServiceSchemaTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    private Path home;

    @Before
    public void setUp() throws Exception {
        home = tmp.newFolder("bridge-home").toPath();
    }

    @After
    public void tearDown() {
        BridgeService.stop();
        BridgeService.lock().lock();
    }

    @Test
    public void fullSessionProducesSchemaValidRecords() throws Exception {
        BackendAPI backend = mock(BackendAPI.class);
        IController controller = mock(IController.class);
        ICommunicator comm = mock(ICommunicator.class);
        Settings settings = mock(Settings.class);
        when(backend.getController()).thenReturn(controller);
        when(backend.getSettings()).thenReturn(settings);
        when(controller.getCommunicator()).thenReturn(comm);
        when(settings.getConnectionDriver()).thenReturn(ConnectionDriver.JSERIALCOMM);
        when(settings.getPort()).thenReturn("/dev/cu.usbmodem1101");
        when(settings.getFirmwareVersion()).thenReturn("GRBL");
        Path job = tmp.newFile("job.nc").toPath();
        Files.writeString(job, "G21\nG0 X1\nM6 T2\nM2\n");
        File jobFile = job.toFile();
        when(backend.getGcodeFile()).thenReturn(jobFile);

        BridgeService.start(backend, new SessionLog(home));
        SessionLog log = BridgeService.sessionLog();
        Capture c = BridgeService.capture();

        BridgeService.lock().unlock();
        BridgeService.lock().lock();

        c.UGSEvent(new ControllerStateEvent(ControllerState.CONNECTING, ControllerState.DISCONNECTED));
        c.onMessage(MessageType.INFO, "*** Connecting to jserialcomm:///dev/cu.usbmodem1101:115200\n");
        c.rawResponseListener("Grbl 1.1h ['$' for help]");
        GcodeCommand info = new GcodeCommand("$I");
        c.commandSent(info);
        c.rawResponseListener("[VER:1.1h.20190825:]");
        c.rawResponseListener("ok");
        info.appendResponse("[VER:1.1h.20190825:]");
        info.appendResponse("ok");
        c.UGSEvent(new CommandEvent(CommandEventType.COMMAND_COMPLETE, info));
        c.UGSEvent(new FirmwareSettingEvent(new FirmwareSetting("$13", "0")));
        c.UGSEvent(new ControllerStateEvent(ControllerState.ALARM, ControllerState.CONNECTING));
        c.rawResponseListener("<Alarm|MPos:0.000,0.000,0.000|FS:0,0|Pn:P|WCO:-10.000,-10.000,-5.000>");
        c.rawResponseListener("<Idle|MPos:0.000,0.000,0.000|Bf:15,128|FS:0,0|Ov:100,100,100|A:SF>");
        c.UGSEvent(new ControllerStatusEvent(ControllerStatus.EMPTY_CONTROLLER_STATUS, ControllerStatus.EMPTY_CONTROLLER_STATUS));
        c.UGSEvent(new ControllerStateEvent(ControllerState.IDLE, ControllerState.ALARM));
        c.realtimeByteSent((byte) 0x18);

        c.UGSEvent(new FileStateEvent(FileState.FILE_LOADED));
        c.UGSEvent(new StreamEvent(StreamEventType.STREAM_STARTED));
        GcodeCommand l1 = new GcodeCommand("G0 X1", "G0 X1", "", 1);
        c.commandSent(l1);
        c.rawResponseListener("error:20");
        l1.appendResponse("error:20");
        c.UGSEvent(new CommandEvent(CommandEventType.COMMAND_COMPLETE, l1));
        c.communicatorPausedOnError();
        c.UGSEvent(new InterceptorStateEvent(InterceptorState.WAITING_FOR_USER, InterceptorState.RUNNING, null,
                new GcodeCommand("M6 T2", "M6 T2", "", 2), new InterceptorPrompt("confirm", List.of(UserResponse.CONTINUE))));
        GcodeCommand skipped = new GcodeCommand("M6 T2", "M6 T2", "", 2);
        c.UGSEvent(new CommandEvent(CommandEventType.COMMAND_SKIPPED, skipped));
        c.rawResponseListener("ALARM:1");
        c.UGSEvent(new AlarmEvent(Alarm.HARD_LIMIT));
        c.UGSEvent(new ProbeEvent(new Position(1, 2, 3, UnitUtils.Units.MM)));
        c.UGSEvent(new SettingChangedEvent());
        c.UGSEvent(new StreamEvent(StreamEventType.STREAM_CANCELED));
        c.UGSEvent(new ControllerStateEvent(ControllerState.DISCONNECTED, ControllerState.ALARM));
        BridgeService.stop();

        List<JsonObject> all = LogFiles.readAll(home);
        Set<String> kinds = all.stream().map(r -> r.get("kind").getAsString()).collect(Collectors.toSet());
        assertEquals(Set.of("session", "authority", "event", "message", "tx", "rx", "command", "status", "file"), kinds);
        List<JsonObject> auth = all.stream().filter(r -> r.get("kind").getAsString().equals("authority")).toList();
        assertEquals(List.of(true, false, true), auth.stream()
                .map(r -> r.getAsJsonObject("data").get("locked").getAsBoolean()).toList());
        assertEquals("bridge", auth.get(0).getAsJsonObject("data").get("by").getAsString());
        assertEquals("operator", auth.get(1).getAsJsonObject("data").get("by").getAsString());
        // startup records are in the -noconn folder with session null; the last is bridge_stop
        assertTrue(all.get(0).get("session").isJsonNull());
        JsonObject last = all.get(all.size() - 1);
        assertEquals("bridge_stop", last.getAsJsonObject("data").get("event").getAsString());
        assertFalse(log.since(0, null, 1).isEmpty());

        // keep a copy for inspection and validate with the Python schema validator
        Path samples = Paths.get("target", "schema-samples");
        Files.createDirectories(samples);
        List<String> args = new ArrayList<>();
        int i = 0;
        for (Path f : LogFiles.sessionFiles(home)) {
            Path dst = samples.resolve("session-" + (i++) + ".jsonl").toAbsolutePath();
            Files.copy(f, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            args.add(dst.toString());
        }
        assertEquals(2, args.size());
        validateWithPython(args);
    }

    private static void validateWithPython(List<String> files) throws Exception {
        Path bridgeDir = findBridgeDir();
        Assume.assumeTrue("bridge/ directory not found", bridgeDir != null);
        String uv = findUv();
        Assume.assumeTrue("uv not found; skipping schema validation", uv != null);
        List<String> cmd = new ArrayList<>(List.of(uv, "run", "--frozen", "python", "scripts/validate_log.py"));
        cmd.addAll(files);
        Process p = new ProcessBuilder(cmd).directory(bridgeDir.toFile()).redirectErrorStream(true).start();
        boolean done = p.waitFor(180, TimeUnit.SECONDS);
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue("validator timed out", done);
        Assume.assumeFalse("uv could not prepare the env: " + out,
                out.contains("No module named") || out.contains("error: Failed to"));
        assertEquals("schema validation failed:\n" + out, 0, p.exitValue());
    }

    private static Path findBridgeDir() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("bridge/scripts/validate_log.py"))) {
                return dir.resolve("bridge");
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static String findUv() {
        List<String> candidates = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String d : path.split(File.pathSeparator)) {
                candidates.add(d + "/uv");
            }
        }
        candidates.add(System.getProperty("user.home") + "/.local/bin/uv");
        candidates.add("/opt/homebrew/bin/uv");
        return candidates.stream().filter(c -> Files.isExecutable(Paths.get(c))).findFirst().orElse(null);
    }
}
