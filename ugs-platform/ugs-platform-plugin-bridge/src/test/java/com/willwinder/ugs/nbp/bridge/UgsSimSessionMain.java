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

import com.willwinder.ugs.nbp.bridge.log.BridgeHome;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;
import com.willwinder.universalgcodesender.GrblController;
import com.willwinder.universalgcodesender.connection.ConnectionDriver;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.MessageListener;
import com.willwinder.universalgcodesender.listeners.UGSEventListener;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.Overrides;
import com.willwinder.universalgcodesender.model.PartialPosition;
import com.willwinder.universalgcodesender.model.UGSEventDispatcher;
import com.willwinder.universalgcodesender.model.UnitUtils;
import com.willwinder.universalgcodesender.model.events.ControllerStateEvent;
import com.willwinder.universalgcodesender.model.events.FileState;
import com.willwinder.universalgcodesender.model.events.FileStateEvent;
import com.willwinder.universalgcodesender.types.GcodeCommand;
import com.willwinder.universalgcodesender.utils.GcodeStreamReader;
import com.willwinder.universalgcodesender.utils.GcodeStreamWriter;
import com.willwinder.universalgcodesender.utils.Settings;

import javax.jmdns.NetworkTopologyDiscovery;
import java.io.File;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * The session driven by {@link UgsSimIntegrationTest}, in its own JVM (so {@code user.home},
 * {@code ugs.bridge.home} and UGS's static state are isolated): UGS's own {@link GrblController} over the
 * TCP driver to {@code ugs-sim} on 127.0.0.1, with the bridge's {@link BridgeService} (Capture + SessionLog)
 * attached through UGS's {@link UGSEventDispatcher} the way {@code GUIBackend.connect} wires it.
 * <p>
 * Sequence: connect (DTR-reset alarm) → $H → jog → jog cancelled with 0x85 → BitSetter probe → G54 →
 * rejected jog → short stream with a feed override, feed hold and resume → soft reset → disconnect.
 * <p>
 * Safety: loopback only; nothing calls SettingsFactory; mDNS must stay off by default (upstream patch P5).
 * As a second guard the jmdns topology is replaced by one that returns no interface and records whether
 * it was asked ({@code MDNS_QUERIED=} on stdout).
 * <p>
 * {@code UgsSimSessionMain <grbl-port> <work-dir>}, with {@code -Dugs.bridge.home}.
 */
public final class UgsSimSessionMain {
    private static final String HOST = "127.0.0.1";
    private static final AtomicBoolean MDNS_QUERIED = new AtomicBoolean();

    private UgsSimSessionMain() {
    }

    public static void main(String[] args) throws Exception {
        guardMdns();
        int port = Integer.parseInt(args[0]);
        Path work = Path.of(args[1]);

        GrblController controller = new GrblController();
        // What GUIBackend applies from the default settings; done by hand to keep Settings off disk.
        controller.setStatusUpdatesEnabled(true);
        controller.setStatusUpdateRate(200);
        UGSEventDispatcher dispatcher = new UGSEventDispatcher();
        controller.addListener(dispatcher);
        controller.getFirmwareSettings().addListener(dispatcher);

        Settings settings = new Settings();
        settings.setConnectionDriver(ConnectionDriver.TCP);
        settings.setPort(HOST);
        settings.setPortRate(String.valueOf(port));
        settings.setFirmwareVersion("GRBL");
        AtomicReference<File> gcodeFile = new AtomicReference<>();
        AtomicReference<File> processedFile = new AtomicReference<>();
        BackendAPI backend = backend(controller, dispatcher, settings, gcodeFile, processedFile);

        BridgeService.start(backend, new SessionLog(BridgeHome.resolve()));

        step("connect tcp://" + HOST + ":" + port);
        controller.openCommPort(ConnectionDriver.TCP, HOST, port);
        waitFor("ALARM after connect (DTR reset)", 30_000, () -> state(controller) == ControllerState.ALARM);

        step("home ($H)");
        controller.performHomingCycle();
        waitFor("IDLE after homing", 30_000, () -> state(controller) == ControllerState.IDLE);

        step("jog X-20 Y-30");
        controller.jogMachine(new PartialPosition(-20.0, -30.0, UnitUtils.Units.MM), 3000);
        Thread.sleep(300);
        waitFor("IDLE after jog", 20_000, () -> state(controller) == ControllerState.IDLE);

        step("long jog, cancelled (0x85)");
        controller.jogMachine(new PartialPosition(-300.0, null, UnitUtils.Units.MM), 600);
        waitFor("JOG", 10_000, () -> state(controller) == ControllerState.JOG);
        controller.cancelJog();
        waitFor("IDLE after jog cancel", 20_000, () -> state(controller) == ControllerState.IDLE);

        step("BitSetter probe");
        send(controller, "G53 G0 Z-5", true);
        send(controller, "G53 G0 X-4 Y-449", true);
        send(controller, "G91 G38.2 Z-90 F800", true);
        send(controller, "G90", true);
        send(controller, "G53 G0 Z-5", true);

        step("set G54, rejected jog");
        send(controller, "G10 L2 P1 X-420 Y-420 Z-55.6", true);
        send(controller, "$J=G91 X1000 F1000", false);

        step("stream with feed override, hold and resume");
        List<String> lines = program();
        Path nc = work.resolve("it-job.nc");
        Files.write(nc, lines, StandardCharsets.UTF_8);
        gcodeFile.set(nc.toFile());
        dispatcher.sendUGSEvent(new FileStateEvent(FileState.FILE_LOADED)); // as GUIBackend.setGcodeFile
        File processed = work.resolve("it-job.nc_ugs_processed").toFile();
        try (GcodeStreamWriter gw = new GcodeStreamWriter(processed)) {
            for (int i = 0; i < lines.size(); i++) {
                gw.addLine(lines.get(i), lines.get(i), "", i);
            }
        }
        processedFile.set(processed);
        controller.queueStream(new GcodeStreamReader(processed, controller.getCommandCreator()));
        controller.beginStreaming();
        Thread.sleep(1000);
        controller.getOverrideManager().sendOverrideCommand(Overrides.CMD_FEED_OVR_COARSE_PLUS);
        Thread.sleep(500);
        controller.pauseStreaming();
        waitFor("HOLD", 10_000, () -> state(controller) == ControllerState.HOLD);
        Thread.sleep(1000);
        controller.resumeStreaming();
        controller.getOverrideManager().sendOverrideCommand(Overrides.CMD_FEED_OVR_RESET);
        waitFor("stream complete", 300_000, () -> !controller.isStreaming());
        waitFor("IDLE after stream", 60_000, () -> state(controller) == ControllerState.IDLE);

        step("soft reset (0x18)");
        controller.softReset();
        Thread.sleep(2000);
        waitFor("IDLE or ALARM after reset", 30_000,
                () -> state(controller) == ControllerState.IDLE || state(controller) == ControllerState.ALARM);
        Thread.sleep(1000);

        step("disconnect");
        ControllerState prev = state(controller);
        dispatcher.sendUGSEvent(new ControllerStateEvent(ControllerState.DISCONNECTED, prev)); // as GUIBackend.disconnect
        controller.closeCommPort();
        Thread.sleep(300);
        BridgeService.stop();
        System.out.println("MDNS_QUERIED=" + MDNS_QUERIED.get());
        System.out.println("SESSION_DONE");
        System.exit(0);
    }

    static List<String> program() {
        List<String> lines = new ArrayList<>(List.of("G21 G90 G94", "G0 Z5", "G0 X0 Y0", "M3 S18000", "G4 P0.5", "G1 Z-1 F500"));
        for (int i = 0; i < 60; i++) {
            lines.add(String.format("G1 X%.3f Y%.3f F2000", (i % 2) * 100.0, i * 0.25));
        }
        lines.add("G2 X0 Y80 I-50 J0");
        lines.addAll(List.of("G0 Z5", "M5", "M2"));
        return lines;
    }

    private static BackendAPI backend(GrblController controller, UGSEventDispatcher dispatcher, Settings settings,
                                      AtomicReference<File> gcodeFile, AtomicReference<File> processedFile) {
        return (BackendAPI) Proxy.newProxyInstance(UgsSimSessionMain.class.getClassLoader(),
                new Class<?>[]{BackendAPI.class}, (proxy, m, a) -> switch (m.getName()) {
                    case "addUGSEventListener" -> {
                        dispatcher.addListener((UGSEventListener) a[0]);
                        yield null;
                    }
                    case "removeUGSEventListener" -> {
                        dispatcher.removeListener((UGSEventListener) a[0]);
                        yield null;
                    }
                    case "addMessageListener" -> {
                        controller.getMessageService().addListener((MessageListener) a[0]);
                        yield null;
                    }
                    case "removeMessageListener" -> {
                        controller.getMessageService().removeListener((MessageListener) a[0]);
                        yield null;
                    }
                    case "isConnected" -> controller.isCommOpen();
                    case "getController" -> controller;
                    case "getSettings" -> settings;
                    case "getGcodeFile" -> gcodeFile.get();
                    case "getProcessedGcodeFile" -> processedFile.get();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == a[0];
                    case "toString" -> "BackendAPI(it)";
                    default -> {
                        Class<?> r = m.getReturnType();
                        if (r == boolean.class) {
                            yield false;
                        }
                        if (r == int.class || r == long.class || r == double.class || r == float.class) {
                            yield 0;
                        }
                        yield null;
                    }
                });
    }

    private static void send(GrblController controller, String gcode, boolean expectOk) throws Exception {
        GcodeCommand command = controller.createCommand(gcode);
        controller.sendCommandImmediately(command);
        waitFor("'" + gcode + "' done", 30_000, command::isDone);
        check(command.isOk() == expectOk, "'" + gcode + "' -> " + command.getResponse().strip().replace('\n', ' '));
        Thread.sleep(400); // let a status report show the motion before waiting for Idle
        waitFor("idle after '" + gcode + "'", 30_000, () -> state(controller) == ControllerState.IDLE);
    }

    private static ControllerState state(GrblController c) {
        return c.getControllerStatus().getState();
    }

    private static void step(String text) {
        System.out.println("== " + text);
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            System.out.println("SESSION_FAILED");
            System.exit(1);
        }
    }

    private static void waitFor(String what, long timeoutMs, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                check(false, "timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
        System.out.println("  ok   " + what);
    }

    /** Second guard behind P5: no interface for jmdns, and a record of whether it was asked at all. */
    private static void guardMdns() {
        NetworkTopologyDiscovery.Factory.setClassDelegate(() -> new NetworkTopologyDiscovery() {
            @Override
            public InetAddress[] getInetAddresses() {
                MDNS_QUERIED.set(true);
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
