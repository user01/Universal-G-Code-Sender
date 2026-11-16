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

import com.google.gson.JsonObject;
import com.willwinder.ugs.nbp.bridge.job.BitSetter;
import com.willwinder.ugs.nbp.bridge.job.JobModel;
import com.willwinder.ugs.nbp.bridge.job.MachineProfile;
import com.willwinder.ugs.nbp.bridge.job.ProbeSequences;
import com.willwinder.ugs.nbp.bridge.job.ResumePlan;
import com.willwinder.ugs.nbp.bridge.job.ResumePlanner;
import com.willwinder.ugs.nbp.bridge.job.ToolInfo;
import com.willwinder.universalgcodesender.services.interceptor.InterceptAbortedException;
import com.willwinder.universalgcodesender.services.interceptor.InterceptorPrompt;
import com.willwinder.universalgcodesender.services.interceptor.UserResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BitSetter tool change and reference measurement (SPEC §11.4, §11.5, D10). Talks to the machine only through
 * {@link MachineIo} and to the operator only through a {@link Prompter}, so the exact G-code order is testable.
 *
 * <p>Tool change, as sent:
 * <pre>
 *   T21                                   tool word of the M6 line, if any (UGS's modal tool number)
 *   M5
 *   G21 G90
 *   G53 G0 Z&lt;safe_z_machine&gt;
 *   G53 G0 X&lt;park x&gt; Y&lt;park y&gt;            (+ G53 G0 Z&lt;park z&gt; if it differs from safe Z)
 *   G4 P0                                 wait until parked
 *   -- operator prompt "Change to T21 ..." (UGS dialog; Claude can't answer it) --
 *   -- probe pin must be clear (re-prompts, then fails) --
 *   $#                                    G54 before
 *   ProbeSequences.bitsetterMeasure       G21 G90, G53 Z safe, G53 XY BitSetter, G91, G38.2 fast, G0 Z+r,
 *                                         G38.2 slow (S_new), G90, G53 G0 Z safe
 *   G10 L2 P1 Z&lt;S_new + ref&gt;
 *   $#                                    verify within 0.01 mm
 *   G53 G0 X&lt;pre-change x&gt; Y&lt;pre-change y&gt;   D10: back over the work at safe Z
 *   G4 P0
 * </pre>
 * Every exit before the measurement leaves the machine parked at safe Z with the spindle off; a failure after it
 * tries {@code G90} + {@code G53 G0 Z<safe>} before giving up. Without a BitSetter reference the routine parks,
 * tells the operator why in the UGS dialog ({@link #STEP_NO_REFERENCE}) and fails, leaving the stream paused.
 *
 * <p>D22: when the M6 asks for the tool that is already in the collet and its length is known (measured in this
 * connection, nothing since that could invalidate it; the runtime decides, see {@code knownLengthTool}), only the
 * tool word is sent: no park, no prompt, no measurement.
 */
public final class ToolChangeRoutine {
    public static final String STEP_CHANGE = "bridge.toolChange.change";
    public static final String STEP_PROBE_PIN = "bridge.toolChange.probePin";
    public static final String STEP_NO_REFERENCE = "bridge.toolChange.noReference";
    /** Note of the {@code tool} record when D22 skips the measurement. */
    public static final String NOTE_SKIPPED = "same tool, length known, measurement skipped";
    static final double VERIFY_TOLERANCE = 0.01;
    private static final Pattern T_WORD = Pattern.compile("(?i)(?<![A-Z])T\\s*0*(\\d+)");
    private static final int PIN_RETRIES = 3;

    /** Asks the operator; throws when the operator aborts. */
    @FunctionalInterface
    public interface Prompter {
        UserResponse ask(InterceptorPrompt prompt) throws InterceptAbortedException;
    }

    /** Side effects for the runtime: log records and origin attribution. */
    public interface Events {
        /** A {@code tool} log record. */
        void tool(JsonObject data);

        /** The next G54 change comes from this routine: record it with this method/tool/note. */
        void expectOrigin(String method, String tool, String note);
    }

    /** Failure that leaves the stream paused (the interceptor turns it into an InterceptException). */
    public static final class RoutineException extends Exception {
        public RoutineException(String message) {
            super(message);
        }

        public RoutineException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * @param tool       tool id now in the collet (e.g. {@code T21}), null if unknown
     * @param label      operator text, e.g. {@code T21 Whiteside RU1600 1/8 in upcut}
     * @param triggerZ   BitSetter trigger machine Z of the new tool ({@code S_new}); null when not measured
     * @param wcoZBefore G54 Z before the change; null when not measured
     * @param wcoZ       G54 Z set for the new tool ({@code S_new + ref}), as read back; null when not measured
     * @param ref        the BitSetter reference used; null when not measured
     * @param returnXy   machine XY returned to (pre-change XY); null when the machine didn't move
     * @param measured   false when D22 skipped the change (same tool, length known)
     */
    public record Result(String tool, String label, Double triggerZ, Double wcoZBefore, Double wcoZ, Double ref,
                         double[] returnXy, boolean measured) {
        static Result skipped(String tool, String label) {
            return new Result(tool, label, null, null, null, null, null, false);
        }
    }

    /** First-tool reference: {@code ref = WCO_z − S}. */
    public record Reference(String tool, double triggerZ, double wcoZ, double ref, Double repeatTriggerZ) {
    }

    private final MachineProfile profile;
    private final MachineIo io;
    private final Prompter prompter;
    private final Events events;
    private final List<String> sent = new ArrayList<>();

    public ToolChangeRoutine(MachineProfile profile, MachineIo io, Prompter prompter, Events events) {
        this.profile = profile;
        this.io = io;
        this.prompter = prompter;
        this.events = events;
    }

    /** Lines sent so far (for logs and the report). */
    public List<String> sent() {
        return List.copyOf(sent);
    }

    /** T number of the tool word in an M6 line ({@code T21 M6}, {@code M6T2}), or -1. */
    public static int toolNumber(String line) {
        if (line == null) {
            return -1;
        }
        Matcher m = T_WORD.matcher(line.replaceAll("\\([^)]*\\)", " "));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** {@code T21 Whiteside RU1600 1/8 in upcut}, from the job's tool table when it has the tool. */
    public static String toolLabel(JobModel job, int number) {
        if (number <= 0) {
            return "the next tool";
        }
        String id = job != null ? job.toolId(number) : ToolInfo.canonicalId(number);
        ToolInfo info = job != null ? job.tools().get(number) : null;
        return info != null && info.name() != null && !info.name().isBlank() ? id + " " + info.name().trim() : id;
    }

    /** The full tool change for an {@code M6} (no tool with a known length). */
    public Result toolChange(String triggerCommand, int fileLine, JobModel job, Double ref)
            throws InterceptAbortedException, RoutineException, InterruptedException {
        return toolChange(triggerCommand, fileLine, job, ref, null);
    }

    /**
     * The tool change for an {@code M6}.
     *
     * @param triggerCommand  the M6 line as streamed
     * @param fileLine        its 1-based file line, or -1
     * @param job             the loaded job (tool names, envelope check), or null
     * @param ref             the BitSetter reference ({@code WCO_z − S}); null fails after parking
     * @param knownLengthTool the tool in the collet whose length is known (D22), or null; when the M6 asks for this
     *                        tool only the tool word is sent
     */
    public Result toolChange(String triggerCommand, int fileLine, JobModel job, Double ref, String knownLengthTool)
            throws InterceptAbortedException, RoutineException, InterruptedException {
        int number = toolNumber(triggerCommand);
        if (number < 0 && job != null && fileLine > 0) {
            number = job.toolChangeTarget(fileLine);
        }
        String tool = number > 0 ? (job != null ? job.toolId(number) : ToolInfo.canonicalId(number)) : null;
        String label = toolLabel(job, number);
        if (number > 0 && knownLengthTool != null && ToolInfo.parseNumber(knownLengthTool) == number) {
            return confirmSameTool(triggerCommand, fileLine, number, tool, label);
        }
        double safe = profile.safeZ();
        double[] pre = io.machinePosition();
        if (pre == null) {
            throw new RoutineException("machine position unknown: can't remember where to return after the change");
        }
        double[] preXy = {BitSetter.round3(pre[0]), BitSetter.round3(pre[1])};

        try {
            if (number >= 0 && toolNumber(triggerCommand) >= 0) {
                send("T" + number);
            }
            send("M5");
            send("G21 G90");
            send("G53 G0 Z" + f3(safe));
            double[] park = profile.toolChangePark;
            if (park != null && park.length >= 2) {
                send("G53 G0 X" + f3(park[0]) + " Y" + f3(park[1]));
                if (park.length >= 3 && Math.abs(park[2] - safe) > 1e-6) {
                    send("G53 G0 Z" + f3(park[2]));
                }
            }
            send("G4 P0");
        } catch (MachineIo.MachineException e) {
            throw new RoutineException("tool change: could not park: " + e.getMessage(), e);
        }

        JsonObject req = toolData("change_requested", tool);
        req.addProperty("label", label);
        req.addProperty("file_line", fileLine > 0 ? fileLine : null);
        req.add("return_xy", Records.array(preXy));
        events.tool(req);

        if (ref == null) {
            String why = "No BitSetter reference: measure the first tool's reference (after BitZero Z) before running "
                    + "a multi-tool job. The machine is parked at safe Z with the spindle off.";
            // the UGS dialog shows only "interception failed" for a failure: say why first (Abort cancels the job)
            prompter.ask(new InterceptorPrompt(STEP_NO_REFERENCE, List.of(UserResponse.CONTINUE, UserResponse.ABORT),
                    "No BitSetter reference",
                    "The job asks for " + label + ", but there is no BitSetter reference to set its Z from.\n\n"
                            + "The spindle is off and the machine is parked at safe Z. Nothing was measured or cut.\n\n"
                            + "Press Abort to cancel the job, or Continue to leave it paused here. Then zero Z on the "
                            + "stock (BitZero), measure the first tool's reference and start the job again."));
            throw new RoutineException(why);
        }

        prompter.ask(new InterceptorPrompt(STEP_CHANGE, List.of(UserResponse.CONTINUE, UserResponse.ABORT),
                "Change to " + label,
                "Change to " + label + ".\n\nThe spindle is off and parked. Install the bit, take the BitZero clip off, "
                        + "then press Continue: the bridge measures the new tool on the BitSetter, sets Z and returns "
                        + "to the work at safe height."));
        events.tool(toolData("confirmed", tool));

        try {
            requireProbeClear(label);
            double[] g54 = io.readG54();
            sent.add("$#");
            double wcoZBefore = g54[2];
            double s = measure();
            double wcoZ = BitSetter.wcoZForTool(s, ref);
            if (job != null && fileLine > 0 && fileLine < job.lineCount()) {
                List<String> warnings = new ArrayList<>();
                ResumePlan.Check env = ResumePlanner.envelopeCheck(job, profile, fileLine + 1,
                        new double[]{g54[0], g54[1], wcoZ}, warnings);
                if (!env.ok) {
                    throw new RoutineException("After measuring " + label + " (Z offset " + f3(wcoZ)
                            + ") the rest of the program leaves the envelope: " + env.detail);
                }
            }
            events.expectOrigin("g10", tool, String.format(Locale.ROOT,
                    "BitSetter tool change to %s: S=%.3f, ref=%.3f", tool, s, ref));
            send(BitSetter.setWcoZCommand("G54", wcoZ));
            double readZ = io.readG54()[2];
            sent.add("$#");
            if (Math.abs(readZ - wcoZ) > VERIFY_TOLERANCE) {
                throw new RoutineException(String.format(Locale.ROOT,
                        "G54 Z reads %.3f after setting %.3f (more than %.2f mm off)", readZ, wcoZ, VERIFY_TOLERANCE));
            }
            JsonObject m = toolData("measured", tool);
            m.addProperty("trigger_mz", s);
            m.addProperty("ref", ref);
            m.addProperty("wco_z", readZ);
            m.addProperty("wco_z_before", BitSetter.round3(wcoZBefore));
            events.tool(m);

            send("G53 G0 X" + f3(preXy[0]) + " Y" + f3(preXy[1]));
            send("G4 P0");
            return new Result(tool, label, s, BitSetter.round3(wcoZBefore), readZ, ref, preXy, true);
        } catch (MachineIo.MachineException e) {
            retreat();
            throw new RoutineException("tool change failed: " + e.getMessage(), e);
        } catch (RoutineException e) {
            retreat();
            throw e;
        }
    }

    /** D22: the requested tool is in the collet with a known length; send its tool word and carry on. */
    private Result confirmSameTool(String triggerCommand, int fileLine, int number, String tool, String label)
            throws RoutineException, InterruptedException {
        try {
            if (toolNumber(triggerCommand) >= 0) {
                send("T" + number);
            }
        } catch (MachineIo.MachineException e) {
            throw new RoutineException("tool change: could not send the tool word: " + e.getMessage(), e);
        }
        JsonObject d = toolData("confirmed", tool);
        d.addProperty("label", label);
        d.addProperty("file_line", fileLine > 0 ? fileLine : null);
        d.addProperty("measurement_skipped", true);
        d.addProperty("note", NOTE_SKIPPED);
        events.tool(d);
        return Result.skipped(tool, label);
    }

    /**
     * First-tool reference (SPEC §11.4; for the setup checklist): with Z zeroed on the stock (BitZero), measure the
     * tool in the collet and store {@code ref = WCO_z − S}. With {@code verifyRepeat} the tool is measured twice and
     * the two triggers must agree within the profile's {@code repeat_tolerance}. Ends at the pre-measure XY at
     * safe Z.
     */
    public Reference measureReference(String tool, boolean verifyRepeat) throws RoutineException, InterruptedException {
        double[] pre = io.machinePosition();
        try {
            requireProbeClearNoPrompt();
            double wcoZ = BitSetter.round3(io.readG54()[2]);
            sent.add("$#");
            send("M5");
            double s = measure();
            Double s2 = null;
            if (verifyRepeat) {
                s2 = measure();
                BitSetter.Repeat r = BitSetter.verifyRepeat(s, s2, profile.repeatTolerance());
                if (!r.ok()) {
                    throw new RoutineException(String.format(Locale.ROOT,
                            "BitSetter repeat check failed: %.3f vs %.3f (%.3f mm > %.3f)", s, s2, r.difference(),
                            r.tolerance()));
                }
            }
            double ref = BitSetter.reference(wcoZ, s);
            JsonObject d = toolData("reference_set", tool);
            d.addProperty("trigger_mz", s);
            d.addProperty("ref", ref);
            d.addProperty("wco_z", wcoZ);
            if (s2 != null) {
                d.addProperty("repeat_trigger_mz", s2);
            }
            events.tool(d);
            if (pre != null) {
                send("G53 G0 X" + f3(pre[0]) + " Y" + f3(pre[1]));
                send("G4 P0");
            }
            return new Reference(tool, s, wcoZ, ref, s2);
        } catch (MachineIo.MachineException e) {
            retreat();
            throw new RoutineException("reference measurement failed: " + e.getMessage(), e);
        } catch (RoutineException e) {
            retreat();
            throw e;
        }
    }

    /** One BitSetter measurement (two-pass, bounded); returns S (machine Z of the slow pass's contact). */
    double measure() throws MachineIo.MachineException, InterruptedException, RoutineException {
        ProbeSequences.ProbeSequence seq;
        try {
            seq = ProbeSequences.bitsetterMeasure(profile);
        } catch (IllegalStateException e) {
            throw new RoutineException("BitSetter profile: " + e.getMessage());
        }
        Double s = null;
        for (int i = 0; i < seq.commands().size(); i++) {
            String c = seq.commands().get(i);
            if (i == seq.fastProbeIndex() || i == seq.slowProbeIndex()) {
                sent.add(c);
                double[] p = io.probe(c);
                if (i == seq.slowProbeIndex()) {
                    s = BitSetter.round3(p[2]);
                }
            } else {
                send(c);
            }
        }
        if (s == null) {
            throw new RoutineException("BitSetter sequence has no slow probe");
        }
        return s;
    }

    private void requireProbeClear(String label) throws InterceptAbortedException, RoutineException {
        String pin = pin();
        for (int i = 0; io.pinActive(pin); i++) {
            if (i >= PIN_RETRIES) {
                throw new RoutineException("probe input " + pin + " stays active: check the BitSetter and the BitZero clip");
            }
            prompter.ask(new InterceptorPrompt(STEP_PROBE_PIN, List.of(UserResponse.CONTINUE, UserResponse.ABORT),
                    "Probe input is active",
                    "The probe input (" + pin + ") is active: the BitSetter is pressed or the BitZero clip touches "
                            + "the bit. Clear it, then press Continue to measure " + label + "."));
        }
    }

    private void requireProbeClearNoPrompt() throws RoutineException {
        if (io.pinActive(pin())) {
            throw new RoutineException("probe input " + pin() + " is active: clear the BitSetter / BitZero clip first");
        }
    }

    private String pin() {
        return profile.bitsetter != null && profile.bitsetter.pin != null ? profile.bitsetter.pin : "P";
    }

    /** Best effort after a failure: absolute mode and safe Z (fails silently in alarm). */
    private void retreat() throws InterruptedException {
        try {
            send("G90");
            send("G53 G0 Z" + f3(profile.safeZ()));
        } catch (MachineIo.MachineException ignored) {
            // alarm (e.g. probe without contact): the controller refuses motion; the operator must reset
        }
    }

    private void send(String line) throws MachineIo.MachineException, InterruptedException {
        sent.add(line);
        io.send(line);
    }

    private static JsonObject toolData(String event, String tool) {
        JsonObject d = new JsonObject();
        d.addProperty("event", event);
        d.addProperty("tool", tool);
        return d;
    }

    static String f3(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
