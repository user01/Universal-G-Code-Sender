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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.willwinder.ugs.nbp.bridge.log.LogFiles;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Opt-in integration test (S-1): UGS's own GrblController drives {@code ugs-sim} over the TCP driver with the
 * bridge's Capture and SessionLog attached, and the session log is checked against the simulator's own byte
 * log ({@code -vv}), which is the ground truth for what went over the wire.
 * <p>
 * Run with {@code -Dugs.bridge.it=true} or {@code UGS_SIM_IT=1}, e.g.
 * {@code bridge/scripts/dev.sh mvn -B -o -q -pl ugs-core,ugs-platform/ugs-platform-plugin-bridge -am package
 * -Dtest=UgsSimIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dugs.bridge.it=true}.
 * Needs {@code uv} (the simulator runs with {@code uv run --frozen ugs-sim} from {@code bridge/}).
 * <p>
 * Safety: the simulator binds 127.0.0.1 on ephemeral ports; the UGS session runs in a child JVM
 * ({@link UgsSimSessionMain}) whose {@code user.home} and {@code ugs.bridge.home} are empty temp folders
 * ({@code user.home} must still be empty afterwards); mDNS stays off (upstream patch P5, checked); both
 * processes are killed at the end. Output is kept in {@code target/ugs-sim-it/}.
 * <p>
 * Asserts: every line UGS wrote is a {@code tx} record and every non-status controller line an {@code rx}
 * record, in wire order; every real-time byte except {@code ?} polls is a {@code tx} record with
 * {@code realtime}; every {@code rx} names the command GRBL was answering (FIFO, cleared by a reset);
 * console echoes are not logged; all records validate against the schema.
 */
public class UgsSimIntegrationTest {
    private static final Pattern LISTENING = Pattern.compile("^LISTENING 127\\.0\\.0\\.1:(\\d+)$");
    private static final Pattern SIM_LOG = Pattern.compile("^\\S+ \\S+ (rx|tx) (.*)$");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    static boolean enabled() {
        return Boolean.getBoolean("ugs.bridge.it") || "1".equals(System.getenv("UGS_SIM_IT"));
    }

    @Test
    public void ugsDrivesTheSimulatorAndTheLogMatchesTheWire() throws Exception {
        Assume.assumeTrue("opt-in: -Dugs.bridge.it=true or UGS_SIM_IT=1", enabled());
        Path bridgeDir = findBridgeDir();
        assertNotNull("bridge/ directory not found", bridgeDir);
        String uv = findUv();
        assertNotNull("uv not found", uv);

        Path home = tmp.newFolder("user-home").toPath();
        Path bridgeHome = tmp.newFolder("bridge-home").toPath();
        Path work = tmp.newFolder("work").toPath();
        Path simErr = work.resolve("sim.err");
        Path sessionOut = work.resolve("session.out");

        Process sim = null;
        Process session = null;
        boolean finished;
        try {
            sim = new ProcessBuilder(uv, "run", "--frozen", "ugs-sim", "--host", "127.0.0.1", "--port", "0",
                    "--time-scale", "20", "-vv")
                    .directory(bridgeDir.toFile())
                    .redirectError(simErr.toFile())
                    .start();
            int port = awaitPort(sim, 120);

            String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
            session = new ProcessBuilder(java, "-Duser.home=" + home, "-Dugs.bridge.home=" + bridgeHome,
                    "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                    UgsSimSessionMain.class.getName(), String.valueOf(port), work.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(sessionOut.toFile())
                    .start();
            finished = session.waitFor(300, TimeUnit.SECONDS);
        } finally {
            kill(session);
            kill(sim);
        }

        List<String> out = Files.readAllLines(sessionOut, StandardCharsets.UTF_8);
        List<String> simLines = Files.readAllLines(simErr, StandardCharsets.UTF_8);
        List<JsonObject> records = new ArrayList<>(LogFiles.readAll(bridgeHome));
        records.sort(Comparator.comparingLong(r -> r.get("seq").getAsLong()));
        Report report = analyze(simLines, records);

        // keep the evidence
        Path keep = Paths.get("target", "ugs-sim-it").toAbsolutePath();
        Files.createDirectories(keep);
        Files.copy(simErr, keep.resolve("sim.err"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(sessionOut, keep.resolve("session.out"), StandardCopyOption.REPLACE_EXISTING);
        List<String> logFiles = new ArrayList<>();
        int n = 0;
        for (Path f : LogFiles.sessionFiles(bridgeHome)) {
            Path dst = keep.resolve("session-" + (n++) + ".jsonl");
            Files.copy(f, dst, StandardCopyOption.REPLACE_EXISTING);
            logFiles.add(dst.toString());
        }
        Files.writeString(keep.resolve("summary.txt"), report.summary());
        System.out.println(report.summary());

        List<String> problems = new ArrayList<>(report.problems);
        if (!finished || !out.contains("SESSION_DONE")) {
            problems.add("UGS session did not finish:\n  " + String.join("\n  ", out));
        }
        if (!out.contains("MDNS_QUERIED=false")) {
            problems.add("mDNS: the network topology was queried (P5 not effective)");
        }
        try (Stream<Path> s = Files.list(home)) {
            List<Path> written = s.toList();
            if (!written.isEmpty()) {
                problems.add("something wrote under user.home: " + written);
            }
        }
        String validation = validate(bridgeDir, uv, logFiles);
        if (validation != null) {
            problems.add(validation);
        }
        if (!problems.isEmpty()) {
            fail(problems.size() + " problem(s):\n- " + String.join("\n- ", problems));
        }
    }

    // ------------------------------------------------------------------ analysis

    /** What the analysis found; {@link #problems} is empty when the log matches the wire. */
    static final class Report {
        final List<String> problems = new ArrayList<>();
        int wireLines;
        int wireStatusQueryLines;
        int statusPolls;
        final Map<String, Integer> realtimeOnWire = new TreeMap<>();
        int realtimeTx;
        int txLines;
        int controllerLines;
        int rxRecords;
        int rxRight;
        int rxWrong;
        int rxMissing;
        int acks;
        int acksRight;
        int acksWrong;
        int messages;
        int echoMessages;
        final List<String> wrongExamples = new ArrayList<>();

        String summary() {
            StringBuilder b = new StringBuilder();
            b.append("UGS -> controller: ").append(wireLines).append(" lines (").append(wireStatusQueryLines)
                    .append(" of them '?' lines), real-time bytes ").append(realtimeOnWire)
                    .append(", '?' polls ").append(statusPolls).append('\n');
            b.append("  tx line records ").append(txLines).append(", tx real-time records ").append(realtimeTx).append('\n');
            b.append("controller -> UGS (without <...> reports): ").append(controllerLines)
                    .append(" lines; rx records ").append(rxRecords).append('\n');
            b.append("controller lines missing from rx: ").append(rxMissing).append('\n');
            b.append("rx cmd_id right ").append(rxRight).append(", wrong ").append(rxWrong)
                    .append("; of them ok/error answers: right ").append(acksRight).append(", wrong ").append(acksWrong)
                    .append(" (of ").append(acks).append(")\n");
            b.append("message records ").append(messages).append(", console echoes among them ").append(echoMessages).append('\n');
            for (String w : wrongExamples) {
                b.append("  ").append(w).append('\n');
            }
            b.append(problems.isEmpty() ? "OK\n" : "PROBLEMS:\n- " + String.join("\n- ", problems) + "\n");
            return b.toString();
        }
    }

    /**
     * Compares the session records with the simulator's byte log. The simulator logs {@code rx b'...'} for every
     * chunk UGS wrote and {@code tx <line>} for every line it sent, in its processing order, so GRBL's answer
     * order can be replayed: lines are answered first in, first out, and a {@code Grbl ...} welcome (reset)
     * drops whatever was waiting.
     */
    static Report analyze(List<String> simLog, List<JsonObject> records) {
        Report r = new Report();
        List<String> wire = new ArrayList<>();
        List<String> realtime = new ArrayList<>();
        List<String> ctrl = new ArrayList<>();
        List<Integer> ctrlAnswers = new ArrayList<>(); // wire line index the controller line belongs to, or -1
        Deque<Integer> waiting = new ArrayDeque<>();
        ByteArrayOutputStream cur = new ByteArrayOutputStream();
        for (String l : simLog) {
            Matcher m = SIM_LOG.matcher(l);
            if (!m.matches()) {
                continue;
            }
            if (m.group(1).equals("rx")) {
                byte[] chunk = pyBytes(m.group(2));
                for (int i = 0; i < chunk.length; i++) {
                    int b = chunk[i] & 0xff;
                    boolean nextIsNewline = i + 1 < chunk.length && chunk[i + 1] == '\n';
                    if (b == '\n') {
                        wire.add(cur.toString(StandardCharsets.US_ASCII).strip());
                        waiting.addLast(wire.size() - 1);
                        cur.reset();
                    } else if (b == '?' && cur.size() == 0 && nextIsNewline) {
                        cur.write(b); // GetStatusCommand: '?' sent as a whole line, answered with ok
                    } else if (b == '?') {
                        r.statusPolls++;
                    } else if (b == '!' || b == '~' || b == 0x18 || b >= 0x80) {
                        realtime.add(hex(b));
                        r.realtimeOnWire.merge(hex(b), 1, Integer::sum);
                    } else if (b != '\r') {
                        cur.write(b);
                    }
                }
            } else {
                String line = m.group(2).strip();
                if (line.startsWith("<")) {
                    continue;
                }
                ctrl.add(line);
                if (line.startsWith("Grbl ")) {
                    waiting.clear();
                    ctrlAnswers.add(-1);
                } else if (!waiting.isEmpty()) {
                    ctrlAnswers.add(isAck(line) ? waiting.removeFirst() : waiting.peekFirst());
                } else {
                    ctrlAnswers.add(-1);
                }
            }
        }
        r.wireLines = wire.size();
        r.wireStatusQueryLines = (int) wire.stream().filter("?"::equals).count();
        r.controllerLines = ctrl.size();

        List<JsonObject> txLines = new ArrayList<>();
        List<String> txRealtime = new ArrayList<>();
        List<JsonObject> rx = new ArrayList<>();
        for (JsonObject rec : records) {
            String kind = rec.get("kind").getAsString();
            JsonObject d = rec.getAsJsonObject("data");
            if (kind.equals("tx")) {
                if (d.has("realtime") && !d.get("realtime").isJsonNull()) {
                    txRealtime.add(d.get("realtime").getAsString());
                } else {
                    txLines.add(d);
                }
            } else if (kind.equals("rx")) {
                rx.add(d);
            } else if (kind.equals("message")) {
                r.messages++;
                String text = d.get("text").getAsString().strip();
                if (text.contains(">>> ") || text.equalsIgnoreCase("ok")) {
                    r.echoMessages++;
                }
            }
        }
        r.txLines = txLines.size();
        r.realtimeTx = txRealtime.size();
        r.rxRecords = rx.size();

        // 1. every wire line is a tx record, in order
        List<String> txText = txLines.stream().map(d -> d.get("line").getAsString().strip()).toList();
        if (!txText.equals(wire)) {
            r.problems.add("tx lines differ from the wire: " + firstDifference(wire, txText));
        }
        // cmd_id of each wire line (aligned in order, so a skipped line leaves a gap instead of a shift)
        String[] wireCmd = new String[wire.size()];
        for (int w = 0, t = 0; w < wire.size() && t < txLines.size(); w++) {
            if (wire.get(w).equals(txText.get(t))) {
                JsonElement id = txLines.get(t++).get("cmd_id");
                wireCmd[w] = id == null || id.isJsonNull() ? null : id.getAsString();
            } else {
                wireCmd[w] = "<line not logged>";
            }
        }
        // 2. every real-time byte except '?' is a tx record, in order
        if (!txRealtime.equals(realtime)) {
            r.problems.add("real-time tx differs from the wire: " + firstDifference(realtime, txRealtime));
        }
        for (String needed : List.of("0x21", "0x7e", "0x85", "0x18", "0x91")) {
            if (!txRealtime.contains(needed)) {
                r.problems.add("no real-time " + needed + " in the log");
            }
        }
        // 3. every non-status controller line is an rx record, in order
        List<String> rxText = rx.stream().map(d -> d.get("line").getAsString()).toList();
        if (!rxText.equals(ctrl)) {
            r.problems.add("rx lines differ from the wire: " + firstDifference(ctrl, rxText));
        }
        // 4. every rx names the command GRBL was answering (aligned in order, so a missing line is not a shift)
        int missing = 0;
        for (int i = 0, j = 0; i < ctrl.size(); i++) {
            if (j >= rx.size() || !ctrl.get(i).equals(rxText.get(j))) {
                missing++;
                continue;
            }
            int w = ctrlAnswers.get(i);
            String expected = w < 0 ? null : wireCmd[w];
            JsonElement got = rx.get(j++).get("cmd_id");
            String actual = got == null || got.isJsonNull() ? null : got.getAsString();
            boolean ack = isAck(ctrl.get(i)) && !ctrl.get(i).toLowerCase(Locale.ROOT).startsWith("alarm");
            boolean right = Objects.equals(expected, actual);
            if (right) {
                r.rxRight++;
            } else {
                r.rxWrong++;
                if (r.wrongExamples.size() < 6) {
                    r.wrongExamples.add("rx '" + ctrl.get(i) + "' (wire #" + i + ") answers " + expected
                            + (w < 0 ? "" : " ('" + wire.get(w) + "')") + ", logged as " + actual);
                }
            }
            if (ack) {
                r.acks++;
                if (right) {
                    r.acksRight++;
                } else {
                    r.acksWrong++;
                }
            }
        }
        r.rxMissing = missing;
        if (r.rxWrong > 0) {
            r.problems.add(r.rxWrong + " rx records name the wrong command");
        }
        // 5. D16: no console echoes
        if (r.echoMessages > 0) {
            r.problems.add(r.echoMessages + " console echoes logged as message records");
        }
        return r;
    }

    static boolean isAck(String line) {
        String l = line.strip().toLowerCase(Locale.ROOT);
        return l.equals("ok") || l.startsWith("error") || l.startsWith("alarm:");
    }

    private static String hex(int b) {
        return String.format("0x%02x", b);
    }

    private static String firstDifference(List<String> expected, List<String> actual) {
        for (int i = 0; i < Math.min(expected.size(), actual.size()); i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                return "#" + i + " wire " + quote(expected.get(i)) + ", log " + quote(actual.get(i))
                        + " (wire " + expected.size() + ", log " + actual.size() + ")";
            }
        }
        return "wire has " + expected.size() + ", log has " + actual.size();
    }

    private static String quote(String s) {
        return "'" + s + "'";
    }

    /** Decodes a Python bytes literal as logged by {@code %r}: {@code b'G0 X1\n'}, {@code b'\x85'}. */
    static byte[] pyBytes(String literal) {
        String s = literal.strip();
        if (!s.startsWith("b") || s.length() < 3) {
            throw new IllegalArgumentException("not a bytes literal: " + literal);
        }
        s = s.substring(2, s.length() - 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                out.write(c);
                continue;
            }
            char e = s.charAt(++i);
            switch (e) {
                case 'n' -> out.write('\n');
                case 'r' -> out.write('\r');
                case 't' -> out.write('\t');
                case 'x' -> {
                    out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 2;
                }
                default -> out.write(e); // \\ \' \"
            }
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ processes

    private static int awaitPort(Process sim, int seconds) throws Exception {
        LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(sim.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    lines.add(l);
                }
            } catch (IOException ignored) {
                // process ended
            }
        }, "ugs-sim-stdout");
        reader.setDaemon(true);
        reader.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            String l = lines.poll(200, TimeUnit.MILLISECONDS);
            if (l != null) {
                Matcher m = LISTENING.matcher(l.strip());
                if (m.matches()) {
                    return Integer.parseInt(m.group(1));
                }
            } else if (!sim.isAlive()) {
                fail("ugs-sim exited with " + sim.exitValue());
            }
        }
        fail("ugs-sim did not report its port within " + seconds + " s");
        return -1;
    }

    private static void kill(Process p) throws InterruptedException {
        if (p == null) {
            return;
        }
        List<ProcessHandle> children = p.descendants().toList();
        p.destroy();
        children.forEach(ProcessHandle::destroy);
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            p.waitFor(10, TimeUnit.SECONDS);
        }
        for (ProcessHandle c : children) {
            if (c.isAlive()) {
                c.destroyForcibly();
            }
        }
    }

    /** Runs bridge/scripts/validate_log.py; returns null if all records validate. */
    private static String validate(Path bridgeDir, String uv, List<String> files) throws Exception {
        if (files.isEmpty()) {
            return "no session log written";
        }
        List<String> cmd = new ArrayList<>(List.of(uv, "run", "--frozen", "python", "scripts/validate_log.py"));
        cmd.addAll(files);
        Process p = new ProcessBuilder(cmd).directory(bridgeDir.toFile()).redirectErrorStream(true).start();
        boolean done = p.waitFor(180, TimeUnit.SECONDS);
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!done) {
            p.destroyForcibly();
            return "schema validator timed out";
        }
        return p.exitValue() == 0 ? null : "schema validation failed:\n" + out;
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

    @Test
    public void pythonBytesLiteralsDecode() {
        assertTrue(java.util.Arrays.equals("G0 X1\n".getBytes(StandardCharsets.US_ASCII), pyBytes("b'G0 X1\\n'")));
        assertTrue(java.util.Arrays.equals(new byte[]{(byte) 0x85}, pyBytes("b'\\x85'")));
        assertTrue(java.util.Arrays.equals("$J='a'\n".getBytes(StandardCharsets.US_ASCII), pyBytes("b\"$J='a'\\n\"")));
    }
}
