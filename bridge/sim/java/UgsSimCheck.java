/*
 * ugs-bridge: drive bridge/sim (ugs-sim) with UGS's own GrblController over the TCP driver.
 *
 *   UgsSimCheck <grbl-port> <control-port> [file.nc]
 *
 * Headless, no GUI, no settings: GrblController + GrblCommunicator + TCPConnection only.
 * Safety:
 *  - the TCP driver constructor starts UGS's mDNS listener on every IPv4 interface; we install a
 *    jmdns topology delegate that returns no interfaces, so nothing binds or multicasts;
 *  - nothing here calls SettingsFactory; the run script also points user.home at a temp dir
 *    and checks it stayed empty.
 * Run via bridge/sim/scripts/ugs-compat.sh (it starts and kills the simulator).
 */

import com.willwinder.universalgcodesender.GrblController;
import com.willwinder.universalgcodesender.connection.ConnectionDriver;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.ControllerStatus;
import com.willwinder.universalgcodesender.listeners.DefaultControllerListener;
import com.willwinder.universalgcodesender.model.Alarm;
import com.willwinder.universalgcodesender.model.Axis;
import com.willwinder.universalgcodesender.model.PartialPosition;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils;
import com.willwinder.universalgcodesender.types.GcodeCommand;
import com.willwinder.universalgcodesender.utils.SimpleGcodeStreamReader;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public class UgsSimCheck {
    private static final String HOST = "127.0.0.1";
    private static int controlPort;

    public static void main(String[] args) throws Exception {
        disableMdns();
        int port = Integer.parseInt(args[0]);
        controlPort = Integer.parseInt(args[1]);
        Path file = args.length > 2 ? Path.of(args[2]) : null;

        GrblController controller = new GrblController();
        // What GUIBackend applies from the (default) settings; done by hand to avoid Settings.
        controller.setStatusUpdatesEnabled(true);
        controller.setStatusUpdateRate(200);
        List<String> console = new CopyOnWriteArrayList<>();
        controller.getMessageService().addListener((type, message) -> {
            console.add(message.strip());
            System.out.print("  [ugs " + type + "] " + message);
        });
        List<Position> probes = new CopyOnWriteArrayList<>();
        List<Alarm> alarms = new CopyOnWriteArrayList<>();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        controller.addListener(new DefaultControllerListener() {
            @Override public void probeCoordinates(Position p) { probes.add(p); }
            @Override public void receivedAlarm(Alarm alarm) { alarms.add(alarm); }
            @Override public void commandComplete(GcodeCommand command) {
                completed.incrementAndGet();
                if (command.isError()) {
                    errors.incrementAndGet();
                    System.out.println("  ERROR on '" + command.getCommandString() + "': " + command.getResponse());
                }
            }
        });

        step("connect tcp://" + HOST + ":" + port);
        controller.openCommPort(ConnectionDriver.TCP, HOST, port);
        // Connect = DTR reset on the real machine: the simulator comes up locked in Alarm.
        waitFor("initialized and reporting ALARM", 20_000, () -> state(controller) == ControllerState.ALARM);
        check(controller.getFirmwareSettings().getSoftLimit(Axis.X) == 445.0, "$130 read from $$ = 445");

        step("home ($H)");
        controller.performHomingCycle();
        waitFor("IDLE after homing", 30_000, () -> state(controller) == ControllerState.IDLE);
        Position home = status(controller).getMachineCoord();
        check(near(home.getX(), -1) && near(home.getY(), -1) && near(home.getZ(), -1), "MPos -1,-1,-1 after homing: " + home);

        step("jog X-20 Y-30 (relative, $J=)");
        controller.jogMachine(new PartialPosition(-20.0, -30.0, UnitUtils.Units.MM), 3000);
        Thread.sleep(300);
        waitFor("IDLE after jog", 20_000, () -> state(controller) == ControllerState.IDLE);
        Position jogged = status(controller).getMachineCoord();
        check(near(jogged.getX(), -21) && near(jogged.getY(), -31), "MPos after jog: " + jogged);

        step("BitSetter: park over it and G38.2 (probe pin + PRB)");
        send(controller, "G53 G0 Z-5");
        send(controller, "G53 G0 X-4 Y-449");
        send(controller, "G91 G38.2 Z-90 F800");
        waitFor("probe coordinates", 20_000, () -> !probes.isEmpty());
        double sA = probes.get(probes.size() - 1).getZ();
        waitFor("Pn:P seen by UGS", 5_000, () -> status(controller).getEnabledPins().probe());
        send(controller, "G90");
        System.out.println("  tool A trigger Z = " + sA);

        step("tool change via control port (+12.5 mm) and re-measure");
        control("tool 52.5");
        send(controller, "G53 G0 Z-5");
        send(controller, "G91 G38.2 Z-90 F800");
        waitFor("second probe", 20_000, () -> probes.size() >= 2);
        double sB = probes.get(probes.size() - 1).getZ();
        send(controller, "G90");
        check(near(sB - sA, 12.5), "BitSetter delta = tool length delta (" + (sB - sA) + ")");
        send(controller, "G53 G0 Z-5");

        step("set G54 and stream");
        send(controller, "G10 L2 P1 X-420 Y-420 Z-55.6");
        List<String> lines = file != null ? cleanFile(file) : shortProgram();
        int before = completed.get();
        controller.queueStream(new SimpleGcodeStreamReader(lines.toArray(new String[0])));
        controller.beginStreaming();
        waitFor("stream complete (" + lines.size() + " lines)", 600_000, () -> !controller.isStreaming());
        waitFor("IDLE after stream", 60_000, () -> state(controller) == ControllerState.IDLE);
        int streamed = completed.get() - before;
        check(streamed == lines.size(), "every streamed line completed: " + streamed + "/" + lines.size());
        check(errors.get() == 0, "no error responses");
        check(alarms.isEmpty(), "no alarms: " + alarms);

        String state = control("state");
        check(state.contains("\"rx_overflow\": false"), "simulator saw no RX overflow");
        System.out.println("  sim " + state);

        step("disconnect");
        controller.closeCommPort();
        System.out.println("\nUGS-SIM CHECK PASSED");
        System.exit(0);
    }

    private static List<String> shortProgram() {
        List<String> lines = new ArrayList<>(List.of("G21 G90 G94", "T2", "G0 Z5", "G0 X0 Y0", "M3 S18000", "G4 P0.5", "G1 Z-1 F500"));
        for (int i = 0; i < 300; i++) {
            lines.add(String.format("G1 X%.3f Y%.3f F2000", (i % 2) * 100.0, i * 0.25));
        }
        lines.add("G2 X0 Y80 I-50 J0");
        lines.addAll(List.of("G0 Z5", "M5", "M2"));
        return lines;
    }

    /** Comments, blank lines and M6 (UGS's tool-change interceptor is not in play here). */
    private static List<String> cleanFile(Path file) throws Exception {
        List<String> out = new ArrayList<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.replaceAll("\\([^)]*\\)", "").replaceAll(";.*$", "").trim();
            if (line.isEmpty() || line.matches("(?i).*\\bM0?6\\b.*")) {
                continue;
            }
            out.add(line);
        }
        return out;
    }

    private static void send(GrblController controller, String gcode) throws Exception {
        GcodeCommand command = controller.createCommand(gcode);
        controller.sendCommandImmediately(command);
        waitFor("'" + gcode + "' done", 30_000, command::isDone);
        check(command.isOk(), "'" + gcode + "' -> " + command.getResponse());
        waitFor("idle after '" + gcode + "'", 30_000, () -> state(controller) == ControllerState.IDLE);
    }

    private static String control(String command) throws Exception {
        try (Socket s = new Socket(HOST, controlPort);
             PrintWriter w = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8);
             BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
            w.println(command);
            String reply = r.readLine();
            check(reply != null && reply.startsWith("ok"), "control '" + command + "' -> " + reply);
            return reply;
        }
    }

    private static ControllerStatus status(GrblController c) {
        return c.getControllerStatus();
    }

    private static ControllerState state(GrblController c) {
        return c.getControllerStatus().getState();
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-3;
    }

    private static void step(String text) {
        System.out.println("== " + text);
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            System.out.println("\nUGS-SIM CHECK FAILED");
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

    /** UGS's MdnsService (created by TCPConnection) would open mDNS on every IPv4 interface. */
    private static void disableMdns() {
        NetworkTopologyDiscovery.Factory.setClassDelegate(() -> new NetworkTopologyDiscovery() {
            @Override public InetAddress[] getInetAddresses() { return new InetAddress[0]; }
            @Override public boolean useInetAddress(NetworkInterface ni, InetAddress a) { return false; }
            @Override public void lockInetAddress(InetAddress a) { }
            @Override public void unlockInetAddress(InetAddress a) { }
        });
    }
}
