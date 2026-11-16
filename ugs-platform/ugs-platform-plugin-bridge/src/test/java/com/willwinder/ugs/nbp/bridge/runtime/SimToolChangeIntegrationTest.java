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
package com.willwinder.ugs.nbp.bridge.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.willwinder.ugs.nbp.bridge.capture.Capture;
import com.willwinder.ugs.nbp.bridge.capture.CommandOrigins;
import com.willwinder.ugs.nbp.bridge.job.JobModel;
import com.willwinder.ugs.nbp.bridge.job.JobState;
import com.willwinder.ugs.nbp.bridge.job.MachineProfile;
import com.willwinder.ugs.nbp.bridge.job.OriginStore;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;
import com.willwinder.universalgcodesender.GrblController;
import com.willwinder.universalgcodesender.connection.ConnectionDriver;
import com.willwinder.universalgcodesender.gcode.GcodeParser;
import com.willwinder.universalgcodesender.gcode.processors.CommentProcessor;
import com.willwinder.universalgcodesender.gcode.processors.M30Processor;
import com.willwinder.universalgcodesender.gcode.processors.WhitespaceProcessor;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserUtils;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.UGSEventListener;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UGSEventDispatcher;
import com.willwinder.universalgcodesender.model.events.FileState;
import com.willwinder.universalgcodesender.model.events.FileStateEvent;
import com.willwinder.universalgcodesender.services.interceptor.CommandInterceptorService;
import com.willwinder.universalgcodesender.services.interceptor.InterceptorState;
import com.willwinder.universalgcodesender.services.interceptor.InterceptorStateEvent;
import com.willwinder.universalgcodesender.services.interceptor.UserResponse;
import com.willwinder.universalgcodesender.utils.GcodeStreamReader;
import com.willwinder.universalgcodesender.utils.GcodeStreamWriter;
import com.willwinder.universalgcodesender.utils.Settings;
import org.junit.Assume;
import org.junit.Test;

import javax.jmdns.NetworkTopologyDiscovery;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Opt-in ({@code UGS_SIM_IT=1}): UGS's own GrblController over the TCP driver to {@code bridge/sim}, with the job
 * runtime attached and the BitSetter interceptor registered in a real {@link CommandInterceptorService}. Streams a
 * two-tool program whose first M6 asks for T02, the tool the reference was just measured with: D22 sends only the
 * tool word there (no prompt, no probe). During the T21 prompt the test swaps the simulated bit ({@code tool 52.5}, +12.5 mm) and then
 * confirms the prompt (only this test does that: the bridge has no way to confirm it).
 * <p>
 * Asserts: G54 Z changed by exactly the tool-length delta; the routine returned to the pre-change XY at safe Z
 * (D10); the second tool cut at the same stock top (tool-tip heights from the simulator's physical position).
 * <p>
 * Safety as in {@code bridge/sim/java/UgsSimCheck.java}: loopback only, ephemeral ports, mDNS disabled,
 * {@code user.home} pointed at an empty temp dir (checked afterwards). Artifacts go to {@code target/sim-it/}.
 */
public class SimToolChangeIntegrationTest {
    private static final String HOST = "127.0.0.1";
    private static final double TOOL_A = 40.0;
    private static final double TOOL_B = 52.5;
    private static final double[] G54 = {-300, -380, -55.6}; // work Z0 = tip of the 40 mm tool on the sim's stock top
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private int controlPort;

    @Test
    public void bitsetterToolChangeAgainstTheSimulator() throws Exception {
        Assume.assumeTrue("opt-in: set UGS_SIM_IT=1", "1".equals(System.getenv("UGS_SIM_IT")));
        Path bridgeDir = findBridgeDir();
        Assume.assumeTrue("bridge/ not found", bridgeDir != null);
        Path python = bridgeDir.resolve(".venv/bin/python");
        Assume.assumeTrue("run 'uv sync --all-packages' in bridge/ first", Files.isExecutable(python));

        Path work = Files.createTempDirectory("ugs-sim-it");
        Path userHome = Files.createDirectories(work.resolve("user-home"));
        Path bridgeHome = Files.createDirectories(work.resolve("bridge-home"));
        Path out = Paths.get("target", "sim-it").toAbsolutePath();
        Files.createDirectories(out);
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", userHome.toString());
        disableMdns();

        Process sim = new ProcessBuilder(python.toString(), "-m", "ugs_sim.server", "--host", HOST, "--port", "0",
                "--time-scale", "5", "--tool", String.valueOf(TOOL_A))
                .directory(bridgeDir.toFile())
                .redirectError(work.resolve("sim.err").toFile())
                .start();
        GrblController controller = new GrblController();
        ExecutorService operator = Executors.newSingleThreadExecutor();
        Thread sampler = null;
        SessionLog log = new SessionLog(bridgeHome);
        JobRuntime rt = null;
        Capture capture = null;
        try {
            BufferedReader simOut = new BufferedReader(new InputStreamReader(sim.getInputStream(), StandardCharsets.UTF_8));
            int port = -1;
            for (int i = 0; i < 4 && (port < 0 || controlPort <= 0); i++) {
                String l = simOut.readLine();
                assertNotNull("simulator exited: " + Files.readString(work.resolve("sim.err")), l);
                if (l.startsWith("LISTENING " + HOST + ":")) {
                    port = Integer.parseInt(l.substring(l.lastIndexOf(':') + 1).trim());
                } else if (l.startsWith("CONTROL " + HOST + ":")) {
                    controlPort = Integer.parseInt(l.substring(l.lastIndexOf(':') + 1).trim());
                }
            }
            assertTrue("sim ports", port > 0 && controlPort > 0);

            // ---- a minimal backend around a real controller, interceptor service and event dispatcher
            UGSEventDispatcher dispatcher = new UGSEventDispatcher();
            BackendAPI backend = mock(BackendAPI.class);
            CommandInterceptorService service = new CommandInterceptorService(backend, dispatcher);
            dispatcher.addListener(service);
            Settings settings = mock(Settings.class);
            when(settings.getConnectionDriver()).thenReturn(ConnectionDriver.TCP);
            when(settings.getPort()).thenReturn(HOST);
            when(settings.getPortRate()).thenReturn(String.valueOf(port));
            when(settings.getFirmwareVersion()).thenReturn("GRBL");
            when(backend.getSettings()).thenReturn(settings);
            when(backend.getController()).thenReturn(controller);
            when(backend.getInterceptorService()).thenReturn(service);
            when(backend.isConnected()).thenAnswer(a -> controller.isCommOpen());
            when(backend.isSendingFile()).thenAnswer(a -> controller.isStreaming());
            doAnswer(a -> {
                dispatcher.addListener(a.getArgument(0));
                return null;
            }).when(backend).addUGSEventListener(any(UGSEventListener.class));
            doAnswer(a -> {
                dispatcher.removeListener(a.getArgument(0));
                return null;
            }).when(backend).removeUGSEventListener(any(UGSEventListener.class));
            Path nc = work.resolve("two_tools_T02_then_T21.nc");
            Files.writeString(nc, Fixtures.TWO_TOOLS);
            when(backend.getGcodeFile()).thenReturn(nc.toFile());

            controller.setStatusUpdatesEnabled(true);
            controller.setStatusUpdateRate(100);
            controller.addListener(dispatcher);

            log.start();
            capture = new Capture(log, new CommandOrigins(), "sim-it");
            capture.attach(backend);
            MachineProfile profile = MachineProfile.read(bridgeDir.resolve("profiles/shapeoko4-standard.json"));
            rt = new JobRuntime(backend, bridgeHome, Records.of(log), JobRuntime.SessionInfo.of(log),
                    Clock.systemDefaultZone(), profile);
            rt.attach();

            // ---- operator stand-in: answers the tool-change prompts (swapping the bit for T21)
            List<JsonObject> prompts = new CopyOnWriteArrayList<>();
            List<String> interceptorStates = new CopyOnWriteArrayList<>();
            final InterceptorState[] istate = {InterceptorState.INACTIVE};
            dispatcher.addListener(evt -> {
                if (!(evt instanceof InterceptorStateEvent e)) {
                    return;
                }
                istate[0] = e.getState();
                interceptorStates.add(e.getState().name());
                if (e.getState() != InterceptorState.WAITING_FOR_USER || e.getPrompt().isEmpty()) {
                    return;
                }
                var p = e.getPrompt().get();
                Position mp = controller.getControllerStatus().getMachineCoord();
                JsonObject rec = new JsonObject();
                rec.addProperty("step", p.stepId());
                rec.addProperty("title", p.title());
                rec.addProperty("message", p.message());
                rec.addProperty("mpos", mp.getX() + "," + mp.getY() + "," + mp.getZ());
                rec.addProperty("spindle", String.valueOf(controller.getControllerStatus().getAccessoryStates()));
                prompts.add(rec);
                operator.execute(() -> {
                    try {
                        Thread.sleep(300);
                        if (p.title() != null && p.title().contains("T21")) {
                            control("tool " + TOOL_B);
                        }
                        service.provideUserResponse(UserResponse.CONTINUE);
                    } catch (Exception ex) {
                        service.abort();
                    }
                });
            });

            // ---- connect, home, set G54, reference with the first tool
            controller.openCommPort(ConnectionDriver.TCP, HOST, port);
            waitFor("ALARM after connect", 20_000, () -> state(controller) == ControllerState.ALARM);
            controller.performHomingCycle();
            waitFor("IDLE after homing", 30_000, () -> state(controller) == ControllerState.IDLE);
            try (UgsMachineIo io = new UgsMachineIo(controller, null)) {
                io.send(String.format(java.util.Locale.ROOT, "G10 L2 P1 X%.3f Y%.3f Z%.3f", G54[0], G54[1], G54[2]));
                io.readG54();
            }
            final JobRuntime runtime = rt;
            waitFor("runtime saw G54", 10_000, () -> {
                double[] w = runtime.status().wco();
                return w != null && Math.abs(w[2] - G54[2]) < 1e-6;
            });
            ToolChangeRoutine.Reference ref = rt.measureReference("T02", true).get(120, TimeUnit.SECONDS);
            assertEquals(G54[2] - (-105.0 + TOOL_A), ref.ref(), 0.002); // sim BitSetter top at tip Z -105
            assertTrue("D20: on by default", rt.toolChangesEnabled());
            Thread.sleep(300);

            // ---- stream the two-tool program through the interceptor
            dispatcher.sendUGSEvent(new FileStateEvent(FileState.FILE_LOADED));
            Path streamFile = work.resolve("two_tools.stream");
            GcodeParser gcp = new GcodeParser();
            gcp.addCommandProcessor(new CommentProcessor());
            gcp.addCommandProcessor(new WhitespaceProcessor());
            gcp.addCommandProcessor(new M30Processor());
            try (GcodeStreamWriter w = new GcodeStreamWriter(streamFile.toFile())) {
                GcodeParserUtils.processAndExport(gcp, nc.toFile(), w);
            }
            Map<Double, Double> minTip = new ConcurrentHashMap<>();
            double[] minSpindleWhileResuming = {Double.POSITIVE_INFINITY};
            final boolean[] sampling = {true};
            sampler = new Thread(() -> {
                while (sampling[0]) {
                    try {
                        JsonObject s = simState();
                        JsonArray phys = s.getAsJsonArray("physical");
                        double len = s.get("tool_length_mm").getAsDouble();
                        double z = phys.get(2).getAsDouble();
                        if (istate[0] == InterceptorState.INACTIVE) {
                            minTip.merge(len, z - len, Math::min);
                        } else if (istate[0] == InterceptorState.RESUMING) {
                            minSpindleWhileResuming[0] = Math.min(minSpindleWhileResuming[0], z);
                        }
                        Thread.sleep(5);
                    } catch (Exception e) {
                        return;
                    }
                }
            }, "sim-sampler");
            sampler.setDaemon(true);
            sampler.start();

            long t0 = System.currentTimeMillis();
            controller.queueStream(service.beginJob(new GcodeStreamReader(streamFile.toFile(), controller.getCommandCreator())));
            controller.beginStreaming();
            waitFor("stream complete", 300_000, () -> !controller.isStreaming() && service.getState() == InterceptorState.INACTIVE);
            waitFor("IDLE after stream", 60_000, () -> state(controller) == ControllerState.IDLE);
            long streamMs = System.currentTimeMillis() - t0;
            sampling[0] = false;
            sampler.join(2000);
            rt.awaitEvents();
            Thread.sleep(1500); // journal tick
            rt.awaitEvents();

            double[] g54After;
            try (UgsMachineIo io = new UgsMachineIo(controller, null)) {
                g54After = io.readG54();
            }

            // ---- assertions
            // D22: the first M6 (T02, the reference tool) is confirmed without a prompt; only T21 asks the operator
            assertEquals("one prompt: " + prompts, 1, prompts.size());
            assertEquals("Change to T21 Whiteside RU1600 1/8 in upcut", prompts.get(0).get("title").getAsString());
            JobRuntime.ToolChangeOutcome last = rt.status().lastToolChange();
            assertEquals("changed", last.outcome());
            assertEquals("T21", last.tool());
            double delta = last.wcoZ() - last.wcoZBefore();
            assertEquals("G54 Z moved by the tool-length delta", TOOL_B - TOOL_A, delta, 0.001);
            assertEquals(G54[2] + (TOOL_B - TOOL_A), g54After[2], 0.001);
            assertEquals(G54[0], g54After[0], 0.001);
            assertEquals(G54[1], g54After[1], 0.001);
            // D10: back to the pre-change XY (op 1 ended at work X10 Y20) at safe Z
            assertEquals(G54[0] + 10, last.returnXy()[0], 0.001);
            assertEquals(G54[1] + 20, last.returnXy()[1], 0.001);
            List<String> sent = last.sent();
            int g10 = sent.indexOf(String.format(java.util.Locale.ROOT, "G10 L2 P1 Z%.3f", G54[2] + TOOL_B - TOOL_A));
            assertTrue(sent.toString(), g10 > 0);
            assertEquals(String.format(java.util.Locale.ROOT, "G53 G0 X%.3f Y%.3f", G54[0] + 10, G54[1] + 20), sent.get(g10 + 2));
            assertTrue("no move below safe Z while UGS restored the state: " + minSpindleWhileResuming[0],
                    minSpindleWhileResuming[0] >= profile.safeZ() - 0.001);
            // same stock top: op 1 cut to Z-2 with the 40 mm tool, op 2 to Z-1 with the 52.5 mm tool
            double stockTop = G54[2] - TOOL_A;
            assertEquals("op 1 tip", stockTop - 2, minTip.get(TOOL_A), 0.01);
            assertEquals("op 2 tip", stockTop - 1, minTip.get(TOOL_B), 0.01);

            JobModel job = JobModel.load(nc);
            JobState journal = rt.journal();
            assertEquals("completed", journal.stream);
            assertEquals(job.lineCount(), journal.lineDone);
            assertEquals("T21", journal.tool);
            List<OriginStore.Origin> origins = rt.origins();
            assertTrue(origins.stream().anyMatch(o -> "g10".equals(o.method) && Math.abs(o.wco[2] - g54After[2]) < 1e-6
                    && "T21".equals(o.tool)));

            // ---- artifacts for the review report
            rt.detach();
            capture.detach();
            log.close();
            Path journalFile = Path.of(rt.state().activeJournal);
            Files.copy(journalFile, out.resolve("job-state-it.json"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(bridgeHome.resolve("origins.json"), out.resolve("origins-it.json"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(bridgeHome.resolve("runtime.json"), out.resolve("runtime-it.json.txt"), StandardCopyOption.REPLACE_EXISTING);
            List<Path> sessions;
            try (Stream<Path> s = Files.walk(bridgeHome.resolve("logs"))) {
                sessions = s.filter(p -> p.getFileName().toString().equals(SessionLog.LOG_FILE)).sorted().toList();
            }
            List<String> logCopies = new ArrayList<>();
            for (int i = 0; i < sessions.size(); i++) {
                Path dst = out.resolve("session-it-" + i + ".jsonl");
                Files.copy(sessions.get(i), dst, StandardCopyOption.REPLACE_EXISTING);
                logCopies.add(dst.toString());
            }
            boolean skippedT02 = false;
            for (String c : logCopies) {
                skippedT02 |= Files.readAllLines(Path.of(c)).stream().anyMatch(l -> l.contains("\"measurement_skipped\":true")
                        && l.contains("\"tool\":\"T02\""));
            }
            assertTrue("tool record for the skipped T02 measurement", skippedT02);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("stream_ms", streamMs);
            summary.put("reference", ref);
            summary.put("prompts", prompts.stream().map(JsonObject::toString).toList());
            summary.put("last_tool_change", last);
            summary.put("g54_after", g54After);
            summary.put("min_tip_by_tool_length", minTip);
            summary.put("min_spindle_z_while_resuming", minSpindleWhileResuming[0]);
            summary.put("interceptor_states", interceptorStates);
            summary.put("stock_top_tip_z", stockTop);
            Files.writeString(out.resolve("summary.json"), GSON.toJson(summary));

            // schema checks: log records, journal, origins
            runPython(bridgeDir, python, bridgeDir.resolve("scripts/validate_log.py").toString(), logCopies);
            Path samples = Files.createDirectories(out.resolve("json"));
            Files.copy(out.resolve("job-state-it.json"), samples.resolve("job-state-it.json"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(out.resolve("origins-it.json"), samples.resolve("origins-it.json"), StandardCopyOption.REPLACE_EXISTING);
            Path validator = Paths.get("src/test/python/validate_json_samples.py").toAbsolutePath();
            runPython(bridgeDir, python, validator.toString(), List.of(samples.toString()));
        } finally {
            operator.shutdownNow();
            if (rt != null) {
                rt.detach();
            }
            if (capture != null) {
                capture.detach();
            }
            log.close();
            try {
                controller.closeCommPort();
            } catch (Exception ignored) {
                // already closed
            }
            sim.destroy();
            if (!sim.waitFor(5, TimeUnit.SECONDS)) {
                sim.destroyForcibly();
            }
            System.setProperty("user.home", realHome);
            try (Stream<Path> s = Files.list(userHome)) {
                List<Path> left = s.toList();
                assertTrue("something wrote under user.home: " + left, left.isEmpty());
            }
        }
    }

    private static void runPython(Path bridgeDir, Path python, String script, List<String> args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(python.toString(), script));
        cmd.addAll(args);
        Process p = new ProcessBuilder(cmd).directory(bridgeDir.toFile()).redirectErrorStream(true).start();
        String outText = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue("validator timed out", p.waitFor(120, TimeUnit.SECONDS));
        assertEquals("schema validation failed:\n" + outText, 0, p.exitValue());
    }

    private JsonObject simState() throws Exception {
        String reply = control("state");
        return JsonParser.parseString(reply.substring(reply.indexOf('{'))).getAsJsonObject();
    }

    private String control(String command) throws Exception {
        try (Socket s = new Socket(HOST, controlPort);
             PrintWriter w = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8);
             BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
            w.println(command);
            String reply = r.readLine();
            assertTrue("control '" + command + "' -> " + reply, reply != null && reply.startsWith("ok"));
            return reply;
        }
    }

    private static ControllerState state(GrblController c) {
        return c.getControllerStatus().getState();
    }

    private static void waitFor(String what, long timeoutMs, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }

    private static Path findBridgeDir() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("bridge/sim/src/ugs_sim"))) {
                return dir.resolve("bridge");
            }
            dir = dir.getParent();
        }
        return null;
    }

    /** UGS's MdnsService (created by TCPConnection) would open mDNS on every IPv4 interface. */
    private static void disableMdns() {
        NetworkTopologyDiscovery.Factory.setClassDelegate(() -> new NetworkTopologyDiscovery() {
            @Override
            public InetAddress[] getInetAddresses() {
                return new InetAddress[0];
            }

            @Override
            public boolean useInetAddress(NetworkInterface ni, InetAddress a) {
                return false;
            }

            @Override
            public void lockInetAddress(InetAddress a) {
            }

            @Override
            public void unlockInetAddress(InetAddress a) {
            }
        });
    }
}
