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
package com.willwinder.ugs.nbp.bridge.job;

import com.willwinder.universalgcodesender.gcode.GcodeParser;
import com.willwinder.universalgcodesender.gcode.GcodePreprocessorUtils;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.gcode.util.GcodeParserException;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils;
import com.willwinder.universalgcodesender.types.PointSegment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A G-code job loaded for resume planning: the lines, the modal state and tool position at the start of every
 * line, the loaded tool, operations, tool table, tool-change lines and clearance height.
 *
 * <p>Line numbers are 1-based file lines (the numbering the journal uses). "Start of line N" is the state after
 * lines 1..N-1 have run. Positions are work coordinates in the file's units at that line; {@code NaN} means
 * unknown (before the first move on that axis, or after a {@code G53} machine-coordinate move, which leaves the
 * tool at the machine safe height).
 *
 * <p>Uses UGS's {@link GcodeParser} for modal state and positions; {@code G53} is post-corrected (the UGS parser
 * treats {@code G53 G0 Z-5} as a work-coordinate move).
 */
public final class JobModel {

    public enum Motion {NONE, RAPID, LINEAR, ARC_CW, ARC_CCW, PROBE, OTHER}

    private static final double EPS = 1e-6;

    private final String name;
    private final Path path;
    private final String sha256;
    private final List<String> lines;
    private final int n;

    // Indexed 1..n+1; index n+1 is the state at end of file.
    private final double[] sx;
    private final double[] sy;
    private final double[] sz;
    private final Modal[] modalBefore;
    private final int[] toolBefore;
    private final Motion[] motion;
    private final BitSet g53 = new BitSet();
    private final BitSet m6 = new BitSet();
    private final BitSet explicitMotionWord = new BitSet();
    private final BitSet hasCode = new BitSet();
    private final BitSet positionReset = new BitSet();
    private final BitSet opStart = new BitSet();
    private final Map<Integer, double[]> arcCenters = new HashMap<>();
    private final Map<Integer, Integer> m6Tool = new LinkedHashMap<>();
    private final int[] prevSafe;

    private final List<Operation> operations;
    private final Map<Integer, ToolInfo> tools;
    private final double clearanceZ;
    private final String clearanceSource;
    private final List<String> warnings = new ArrayList<>();

    private JobModel(String name, Path path, String sha256, List<String> lines, JobSidecar sidecar) {
        this.name = name;
        this.path = path;
        this.sha256 = sha256;
        this.lines = Collections.unmodifiableList(lines);
        this.n = lines.size();
        sx = new double[n + 2];
        sy = new double[n + 2];
        sz = new double[n + 2];
        modalBefore = new Modal[n + 2];
        toolBefore = new int[n + 2];
        motion = new Motion[n + 2];

        parseLines();
        HeaderParser headers = HeaderParser.parse(lines);

        // Clearance: sidecar, header, else the highest work Z the program moves to.
        if (sidecar != null && sidecar.clearanceZWork != null) {
            clearanceZ = sidecar.clearanceZWork;
            clearanceSource = "sidecar";
        } else if (headers.clearance != null) {
            clearanceZ = headers.clearance;
            clearanceSource = "header";
        } else {
            double max = Double.NaN;
            for (int i = 2; i <= n + 1; i++) {
                if (!Double.isNaN(sz[i]) && (Double.isNaN(max) || sz[i] > max)) {
                    max = sz[i];
                }
            }
            clearanceZ = Double.isNaN(max) ? 0 : max;
            clearanceSource = "inferred_max_z";
            warnings.add(String.format(Locale.ROOT,
                    "No CLEARANCE header or sidecar clearance_z_work: using the highest work Z in the file (Z%.3f)",
                    clearanceZ));
        }

        tools = buildToolTable(headers, sidecar);
        operations = buildOperations(headers, sidecar);
        for (Operation op : operations) {
            opStart.set(op.startLine());
        }
        prevSafe = new int[n + 2];
        int last = 0;
        for (int i = 1; i <= n + 1; i++) {
            if (i <= n && isSafePoint(i)) {
                last = i;
            }
            prevSafe[i] = last;
        }
    }

    // ---------------------------------------------------------------- loading

    public static JobModel load(Path gcode) throws IOException {
        Path sidecarPath = JobSidecar.locate(gcode);
        return load(gcode, sidecarPath == null ? null : JobSidecar.read(sidecarPath));
    }

    public static JobModel load(Path gcode, JobSidecar sidecar) throws IOException {
        byte[] bytes = Files.readAllBytes(gcode);
        return new JobModel(gcode.getFileName().toString(), gcode, Json.sha256Hex(bytes), splitLines(bytes), sidecar);
    }

    /** For tests and in-memory programs. */
    public static JobModel parse(String name, String program, JobSidecar sidecar) {
        byte[] bytes = program.getBytes(StandardCharsets.UTF_8);
        return new JobModel(name, null, Json.sha256Hex(bytes), splitLines(bytes), sidecar);
    }

    static List<String> splitLines(byte[] bytes) {
        String s = new String(bytes, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>(s.length() / 16);
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                int end = i > start && s.charAt(i - 1) == '\r' ? i - 1 : i;
                out.add(s.substring(start, end));
                start = i + 1;
            }
        }
        if (start < s.length()) {
            out.add(s.substring(start));
        }
        return out;
    }

    /** Removes {@code (...)} comments (nesting allowed), {@code ;} comments and {@code %}. */
    static String stripComments(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        int depth = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && depth > 0) {
                depth--;
            } else if (depth == 0) {
                if (c == ';') {
                    break;
                }
                if (c != '%') {
                    sb.append(c);
                }
            }
        }
        return sb.toString().trim();
    }

    private void parseLines() {
        GcodeParser parser = new GcodeParser();
        Map<Modal, Modal> intern = new HashMap<>();
        int loaded = 0;
        Position nan = new Position(Double.NaN, Double.NaN, Double.NaN, UnitUtils.Units.MM);
        parser.getCurrentState().currentPoint = nan;
        for (int i = 1; i <= n + 1; i++) {
            GcodeState st = parser.getCurrentState();
            Position p = st.currentPoint == null ? nan : st.currentPoint;
            sx[i] = p.x;
            sy[i] = p.y;
            sz[i] = p.z;
            modalBefore[i] = intern.computeIfAbsent(Modal.of(st), k -> k);
            toolBefore[i] = loaded;
            motion[i] = Motion.NONE;
            if (i == n + 1) {
                break;
            }
            String cmd = stripComments(lines.get(i - 1));
            if (cmd.isEmpty()) {
                continue;
            }
            hasCode.set(i);
            List<String> args = GcodePreprocessorUtils.splitCommand(cmd);
            Set<Code> gcodes = GcodePreprocessorUtils.getGCodes(args);
            Set<Code> mcodes = GcodePreprocessorUtils.getMCodes(args);
            boolean isG53 = gcodes.contains(Code.G53);
            boolean resets = gcodes.contains(Code.G28) || gcodes.contains(Code.G30) || gcodes.contains(Code.G92)
                    || gcodes.contains(Code.G10) || gcodes.contains(Code.G28_1);
            for (Code c : gcodes) {
                if (c.getType() == Code.ModalGroup.Motion) {
                    explicitMotionWord.set(i);
                }
            }
            Position before = parser.getCurrentState().currentPoint;
            List<GcodeParser.GcodeMeta> metas;
            try {
                metas = parser.addCommand(cmd, i);
            } catch (GcodeParserException | RuntimeException e) {
                warnings.add("Line " + i + ": UGS parser rejected '" + cmd + "': " + e.getMessage());
                continue;
            }
            for (GcodeParser.GcodeMeta meta : metas) {
                if (meta.point == null || meta.code == null) {
                    continue;
                }
                motion[i] = switch (meta.code) {
                    case G0 -> Motion.RAPID;
                    case G1 -> Motion.LINEAR;
                    case G2 -> Motion.ARC_CW;
                    case G3 -> Motion.ARC_CCW;
                    case G38_2, G38_3, G38_4, G38_5 -> Motion.PROBE;
                    default -> Motion.OTHER;
                };
                PointSegment ps = meta.point;
                if (ps.isArc() && ps.center() != null) {
                    Position c = ps.center();
                    int plane = ps.getPlaneState() == null ? 17 : switch (ps.getPlaneState()) {
                        case ZX, WU -> 18;
                        case YZ, VW -> 19;
                        default -> 17;
                    };
                    arcCenters.put(i, new double[]{c.x, c.y, c.z, plane});
                }
            }
            GcodeState after = parser.getCurrentState();
            if (isG53) {
                g53.set(i);
                // Machine-coordinate move: the work position of the axes it names is unknown afterwards.
                Position cur = after.currentPoint;
                Position fixed = new Position(cur);
                if (args.stream().anyMatch(a -> a.startsWith("X") || a.startsWith("x"))) fixed.x = Double.NaN;
                if (args.stream().anyMatch(a -> a.startsWith("Y") || a.startsWith("y"))) fixed.y = Double.NaN;
                if (args.stream().anyMatch(a -> a.startsWith("Z") || a.startsWith("z"))) fixed.z = Double.NaN;
                if (before != null) {
                    if (!Double.isNaN(fixed.x) && Double.isNaN(before.x)) fixed.x = Double.NaN;
                    if (!Double.isNaN(fixed.y) && Double.isNaN(before.y)) fixed.y = Double.NaN;
                }
                after.currentPoint = fixed;
            }
            if (resets) {
                positionReset.set(i);
                after.currentPoint = new Position(Double.NaN, Double.NaN, Double.NaN, after.getUnits());
                warnings.add("Line " + i + ": '" + cmd + "' changes position or offsets; positions after it are unknown");
            }
            if (mcodes.contains(Code.M6)) {
                m6.set(i);
                loaded = after.toolNumber;
                m6Tool.put(i, loaded);
            }
        }
    }

    // ---------------------------------------------------------------- tools and operations

    private Map<Integer, ToolInfo> buildToolTable(HeaderParser headers, JobSidecar sidecar) {
        Map<Integer, ToolInfo> t = new LinkedHashMap<>();
        if (sidecar != null && sidecar.tools != null) {
            for (JobSidecar.Tool st : sidecar.tools) {
                int num = ToolInfo.parseNumber(st.id);
                if (num >= 0) {
                    t.put(num, new ToolInfo(st.id, num, st.name, st.diameter, st.feed, st.plunge));
                }
            }
        }
        for (ToolInfo h : headers.tools.values()) {
            t.merge(h.number(), h, ToolInfo::merge);
        }
        // (FEEDS: ...) lines belong to the next M6 within 20 lines.
        for (HeaderParser.FeedsLine f : headers.feedsLines) {
            for (Map.Entry<Integer, Integer> e : m6Tool.entrySet()) {
                if (e.getKey() > f.line() && e.getKey() - f.line() <= 20) {
                    int num = e.getValue();
                    t.merge(num, new ToolInfo("T" + num, num, null, null, f.cut(), f.plunge()), ToolInfo::merge);
                    break;
                }
            }
        }
        for (int num : m6Tool.values()) {
            t.putIfAbsent(num, new ToolInfo(ToolInfo.canonicalId(num), num, null, null, null, null));
        }
        return Collections.unmodifiableMap(t);
    }

    private List<Operation> buildOperations(HeaderParser headers, JobSidecar sidecar) {
        List<Operation> ops = new ArrayList<>();
        if (sidecar != null && sidecar.operations != null && !sidecar.operations.isEmpty()) {
            int idx = 1;
            for (JobSidecar.Op o : sidecar.operations) {
                if (o.lines == null || o.lines.length != 2 || o.lines[0] < 1 || o.lines[1] > n || o.lines[0] > o.lines[1]) {
                    warnings.add("Sidecar operation " + o.name + " has invalid lines; ignored");
                    continue;
                }
                int num = ToolInfo.parseNumber(o.tool);
                ToolInfo ti = tools.get(num);
                Double plunge = o.plungeFeed != null ? o.plungeFeed : ti == null ? null : ti.plungeFeed();
                if (plunge == null) {
                    plunge = plungeFromProgram(o.lines[0], o.lines[1]);
                }
                ops.add(new Operation(idx++, o.name, o.tool, num, o.lines[0], o.lines[1], plunge,
                        ti == null ? null : ti.cutFeed(), "sidecar"));
            }
            if (!ops.isEmpty()) {
                return Collections.unmodifiableList(ops);
            }
        }
        if (!headers.opHeaders.isEmpty()) {
            List<HeaderParser.OpHeader> hs = headers.opHeaders;
            for (int k = 0; k < hs.size(); k++) {
                HeaderParser.OpHeader h = hs.get(k);
                int nextStart = k + 1 < hs.size() ? hs.get(k + 1).line() : n + 1;
                int end = endBeforeToolChange(h.line(), nextStart);
                int toolNum = h.toolId() != null ? ToolInfo.parseNumber(h.toolId()) : toolAtFirstMotion(h.line(), end);
                int motionTool = toolAtFirstMotion(h.line(), end);
                if (h.toolId() != null && motionTool > 0 && motionTool != toolNum) {
                    warnings.add("OP " + h.number() + " (" + h.name() + ") header says " + h.toolId()
                            + " but T" + motionTool + " is loaded by M6 at that point");
                }
                ToolInfo ti = tools.get(toolNum);
                String toolId = h.toolId() != null ? h.toolId() : ti != null ? ti.id() : toolNum > 0 ? "T" + toolNum : null;
                Double plunge = h.plungeFeed() != null ? h.plungeFeed() : ti == null ? null : ti.plungeFeed();
                if (plunge == null) {
                    plunge = plungeFromProgram(h.line(), end);
                }
                Double cut = h.cutFeed() != null ? h.cutFeed() : ti == null ? null : ti.cutFeed();
                ops.add(new Operation(k + 1, h.name(), toolId, toolNum, h.line(), end, plunge, cut, "header"));
            }
            return Collections.unmodifiableList(ops);
        }
        if (!headers.freecadOps.isEmpty()) {
            List<HeaderParser.FreecadOp> fs = headers.freecadOps.stream().filter(f -> hasMotion(f.beginLine(), f.finishLine())).toList();
            for (int k = 0; k < fs.size(); k++) {
                HeaderParser.FreecadOp f = fs.get(k);
                int nextStart = k + 1 < fs.size() ? fs.get(k + 1).beginLine() : n + 1;
                int end = endBeforeToolChange(f.beginLine(), nextStart);
                ops.add(fallbackOp(k + 1, f.name(), f.beginLine(), end, "freecad"));
            }
            return Collections.unmodifiableList(ops);
        }
        // Fallback: one segment per tool (split at each M6).
        List<Integer> starts = new ArrayList<>();
        int firstMotion = firstMotionAtOrAfter(1, n);
        if (firstMotion > 0 && (m6Tool.isEmpty() || firstMotion < m6Tool.keySet().iterator().next())) {
            starts.add(1);
        }
        starts.addAll(m6Tool.keySet());
        for (int k = 0; k < starts.size(); k++) {
            int s = starts.get(k);
            int e = k + 1 < starts.size() ? starts.get(k + 1) - 1 : n;
            int toolNum = toolAtFirstMotion(s, e);
            ops.add(fallbackOp(k + 1, "segment " + (k + 1) + (toolNum > 0 ? " (T" + toolNum + ")" : ""), s, e, "tool_change"));
        }
        if (!ops.isEmpty()) {
            warnings.add("No operation headers or sidecar: operations are segments between tool changes");
        }
        return Collections.unmodifiableList(ops);
    }

    private Operation fallbackOp(int index, String opName, int start, int end, String source) {
        int toolNum = toolAtFirstMotion(start, end);
        ToolInfo ti = tools.get(toolNum);
        Double plunge = ti != null && ti.plungeFeed() != null ? ti.plungeFeed() : plungeFromProgram(start, end);
        return new Operation(index, opName, ti != null ? ti.id() : toolNum > 0 ? "T" + toolNum : null, toolNum,
                start, end, plunge, ti == null ? null : ti.cutFeed(), source);
    }

    /** An op ends before the next op, or before the next M6 (minus the comment block in front of it). */
    private int endBeforeToolChange(int start, int nextStart) {
        int end = nextStart - 1;
        for (int i = start + 1; i < nextStart; i++) {
            if (m6.get(i)) {
                end = i - 1;
                while (end > start && !hasCode.get(end)) {
                    end--;
                }
                break;
            }
        }
        return end;
    }

    private boolean hasMotion(int from, int to) {
        return firstMotionAtOrAfter(from, to) > 0;
    }

    private int firstMotionAtOrAfter(int from, int to) {
        for (int i = Math.max(1, from); i <= Math.min(n, to); i++) {
            if (motion[i] != Motion.NONE && !g53.get(i)) {
                return i;
            }
        }
        return -1;
    }

    private int toolAtFirstMotion(int from, int to) {
        int m = firstMotionAtOrAfter(from, to);
        return m > 0 ? toolBefore[m] : toolBefore[Math.min(from, n + 1)];
    }

    /**
     * Plunge feed seen in the program: the F of the first downward Z-only feed move (or first downward feed move)
     * in the range, converted to mm/min. Null if none.
     */
    private Double plungeFromProgram(int from, int to) {
        Double anyDown = null;
        for (int i = from; i <= Math.min(to, n); i++) {
            if (motion[i] != Motion.LINEAR || !lineHasWord(i, 'F')) {
                continue;
            }
            double z0 = sz[i];
            double z1 = sz[i + 1];
            if (Double.isNaN(z0) || Double.isNaN(z1) || z1 >= z0 - EPS) {
                continue;
            }
            double f = modalBefore[i + 1].feed() * (modalBefore[i + 1].isMetric() ? 1 : 25.4);
            boolean zOnly = near(sx[i], sx[i + 1]) && near(sy[i], sy[i + 1]);
            if (zOnly) {
                return f;
            }
            if (anyDown == null) {
                anyDown = f;
            }
        }
        return anyDown;
    }

    private boolean lineHasWord(int line, char letter) {
        String cmd = stripComments(lines.get(line - 1));
        for (String a : GcodePreprocessorUtils.splitCommand(cmd)) {
            if (!a.isEmpty() && Character.toUpperCase(a.charAt(0)) == letter) {
                return true;
            }
        }
        return false;
    }

    private static boolean near(double a, double b) {
        return (Double.isNaN(a) && Double.isNaN(b)) || Math.abs(a - b) < 1e-4;
    }

    // ---------------------------------------------------------------- queries

    public String name() {
        return name;
    }

    public Path path() {
        return path;
    }

    public String sha256() {
        return sha256;
    }

    public int lineCount() {
        return n;
    }

    /** The raw text of line {@code line} (1-based). */
    public String line(int line) {
        return lines.get(line - 1);
    }

    public List<String> lines() {
        return lines;
    }

    public List<Operation> operations() {
        return operations;
    }

    public Map<Integer, ToolInfo> tools() {
        return tools;
    }

    /** Tool id for a T number, as the post/sidecar writes it (T02), else T2. */
    public String toolId(int number) {
        ToolInfo t = tools.get(number);
        return t != null && t.id() != null ? t.id() : number > 0 ? ToolInfo.canonicalId(number) : null;
    }

    /** Lines with {@code M6}, in order. */
    public List<Integer> toolChangeLines() {
        return List.copyOf(m6Tool.keySet());
    }

    /** T number loaded by the M6 at {@code line}, or -1 if the line has no M6. */
    public int toolChangeTarget(int line) {
        return m6Tool.getOrDefault(line, -1);
    }

    public double clearanceZ() {
        return clearanceZ;
    }

    /** {@code sidecar}, {@code header} or {@code inferred_max_z}. */
    public String clearanceSource() {
        return clearanceSource;
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    /** Modal state at the start of {@code line} (1..n+1). */
    public Modal modalAt(int line) {
        return modalBefore[line];
    }

    /** Work position at the start of {@code line}: {x, y, z}, file units, NaN = unknown. */
    public double[] startPosition(int line) {
        return new double[]{sx[line], sy[line], sz[line]};
    }

    public double[] endPosition(int line) {
        return startPosition(line + 1);
    }

    /** T number loaded (by the last M6) at the start of {@code line}; 0 if none yet. */
    public int toolAt(int line) {
        return toolBefore[line];
    }

    public Motion motion(int line) {
        return motion[line];
    }

    public boolean isG53(int line) {
        return g53.get(line);
    }

    public boolean hasM6(int line) {
        return m6.get(line);
    }

    /** True if the line carries an explicit G0/G1/G2/G3/G38.x word (not relying on modal motion). */
    public boolean hasExplicitMotionWord(int line) {
        return explicitMotionWord.get(line);
    }

    public boolean hasCode(int line) {
        return hasCode.get(line);
    }

    public boolean resetsPosition(int line) {
        return positionReset.get(line);
    }

    /** {cx, cy, cz, plane(17|18|19)} for an arc line, else null. */
    public double[] arcCenter(int line) {
        return arcCenters.get(line);
    }

    public boolean isOperationStart(int line) {
        return opStart.get(line);
    }

    /**
     * Safe resume point (SPEC §11.6): absolute distance mode at the start of the line, and either the tool is at
     * or above the clearance height (or at machine safe Z after a {@code G53} retract, or before any Z move), or
     * the line starts an operation.
     */
    public boolean isSafePoint(int line) {
        if (line < 1 || line > n) {
            return false;
        }
        if (modalBefore[line].isIncremental()) {
            return false;
        }
        if (opStart.get(line)) {
            return true;
        }
        double z = sz[line];
        return Double.isNaN(z) || z >= clearanceZ - 1e-4;
    }

    /** True if the tool is at/above clearance (or at machine safe Z) at the start of the line. */
    public boolean isAboveClearance(int line) {
        double z = sz[line];
        return Double.isNaN(z) || z >= clearanceZ - 1e-4;
    }

    /** The last safe point at or before {@code line}, or 0 if there is none. */
    public int previousSafePoint(int line) {
        if (line < 1) {
            return 0;
        }
        return prevSafe[Math.min(line, n + 1)];
    }

    /** The operation containing {@code line}, if any. */
    public Optional<Operation> operationAt(int line) {
        for (Operation op : operations) {
            if (op.contains(line)) {
                return Optional.of(op);
            }
        }
        return Optional.empty();
    }

    /** The operation containing {@code line}, else the next one after it. */
    public Optional<Operation> operationAtOrAfter(int line) {
        Optional<Operation> at = operationAt(line);
        if (at.isPresent()) {
            return at;
        }
        return operations.stream().filter(o -> o.startLine() > line).findFirst();
    }

    /** Finds an operation by name (case-insensitive), or by number ("5", "OP 05"). */
    public Optional<Operation> findOperation(String query) {
        if (query == null) {
            return Optional.empty();
        }
        String q = query.trim();
        for (Operation op : operations) {
            if (op.name().equalsIgnoreCase(q)) {
                return Optional.of(op);
            }
        }
        String digits = q.replaceFirst("(?i)^op\\s*", "");
        try {
            int idx = Integer.parseInt(digits);
            return operations.stream().filter(o -> o.index() == idx).findFirst();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** First line at or after {@code from} with a non-G53 motion, or -1. */
    public int firstCuttingMotion(int from) {
        return firstMotionAtOrAfter(from, n);
    }
}
