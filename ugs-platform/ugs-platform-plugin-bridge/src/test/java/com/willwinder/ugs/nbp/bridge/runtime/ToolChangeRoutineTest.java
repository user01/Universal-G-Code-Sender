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

import com.willwinder.ugs.nbp.bridge.job.JobModel;
import com.willwinder.ugs.nbp.bridge.job.MachineProfile;
import com.willwinder.universalgcodesender.services.interceptor.InterceptAbortedException;
import com.willwinder.universalgcodesender.services.interceptor.InterceptorPrompt;
import com.willwinder.universalgcodesender.services.interceptor.UserResponse;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ToolChangeRoutineTest {
    private final JobModel job = JobModel.parse("two.nc", Fixtures.TWO_TOOLS, null);
    private final FakeMachine m = new FakeMachine();
    private final Fixtures.Events events = new Fixtures.Events();
    private final List<InterceptorPrompt> prompts = new ArrayList<>();
    private final List<List<String>> sentAtPrompt = new ArrayList<>();

    private ToolChangeRoutine routine(UserResponse... answers) {
        List<UserResponse> queue = new ArrayList<>(List.of(answers));
        return new ToolChangeRoutine(Fixtures.profile(), m, p -> {
            prompts.add(p);
            sentAtPrompt.add(List.copyOf(m.log));
            UserResponse r = queue.isEmpty() ? UserResponse.CONTINUE : queue.remove(0);
            if (r == UserResponse.ABORT) {
                throw new InterceptAbortedException("Operator aborted the interceptor routine");
            }
            return r;
        }, events);
    }

    private int line(String text) {
        for (int i = 1; i <= job.lineCount(); i++) {
            if (job.line(i).trim().equals(text)) {
                return i;
            }
        }
        throw new AssertionError(text);
    }

    @Test
    public void thePromptWaitsOutTheRoutersSpinDown() throws Exception {
        // 2026-11-16: the prompt came 1.2 s after M5 while the router still coasted
        MachineProfile p = Fixtures.profile();
        p.spindle.spinDownS = 8.0;
        long[] now = {0};
        // parking takes 3 s of the 8 s spin-down: the routine dwells the other 5 s before the prompt
        FakeMachine fm = m;
        ToolChangeRoutine r = new ToolChangeRoutine(p, fm, q -> {
            prompts.add(q);
            sentAtPrompt.add(List.copyOf(fm.log));
            return UserResponse.CONTINUE;
        }, events, () -> {
            long t = now[0];
            now[0] += 3_000_000_000L;
            return t;
        });
        m.triggerZ = -68.5;
        r.toolChange("T21 M6", line("T21 M6"), job, 10.0);
        assertEquals(List.of("T21", "M5", "G21 G90", "G53 G0 Z-5.000", "G53 G0 X-222.000 Y-440.000", "G4 P0", "G4 P5.0"),
                sentAtPrompt.get(0));
        assertTrue(prompts.get(0).message().contains("Wait for the bit to stop before touching it."));
    }

    @Test
    public void noSpinDownDwellWhenParkingTookLongEnough() throws Exception {
        MachineProfile p = Fixtures.profile();
        p.spindle.spinDownS = 2.0;
        long[] now = {0};
        FakeMachine fm = m;
        ToolChangeRoutine r = new ToolChangeRoutine(p, fm, q -> {
            sentAtPrompt.add(List.copyOf(fm.log));
            return UserResponse.CONTINUE;
        }, events, () -> {
            long t = now[0];
            now[0] += 3_000_000_000L;
            return t;
        });
        m.triggerZ = -68.5;
        r.toolChange("T21 M6", line("T21 M6"), job, 10.0);
        assertEquals("G4 P0", sentAtPrompt.get(0).get(sentAtPrompt.get(0).size() - 1));
    }

    @Test
    public void toolChangeSendsTheExactSequenceAndReturnsToThePreChangeXy() throws Exception {
        m.triggerZ = -68.5;
        ToolChangeRoutine r = routine();
        ToolChangeRoutine.Result res = r.toolChange("T21 M6", line("T21 M6"), job, 10.0);

        assertEquals(List.of(
                "T21",
                "M5",
                "G21 G90",
                "G53 G0 Z-5.000",
                "G53 G0 X-222.000 Y-440.000",
                "G4 P0",
                // operator prompt here
                "$#",
                "G21 G90",
                "G53 G0 Z-5.000",
                "G53 G0 X-4.000 Y-449.000",
                "G91",
                "G38.2 Z-90.000 F800",
                "G0 Z2.000",
                "G38.2 Z-3.000 F100",
                "G90",
                "G53 G0 Z-5.000",
                "G10 L2 P1 Z-58.500",
                "$#",
                "G53 G0 X-150.000 Y-300.000",
                "G4 P0"), m.log);
        assertEquals(m.log, r.sent());
        // the prompt came after parking (spindle off, safe Z, park XY) and before anything else
        assertEquals(1, prompts.size());
        assertEquals(6, sentAtPrompt.get(0).size());
        assertEquals(ToolChangeRoutine.STEP_CHANGE, prompts.get(0).stepId());
        assertEquals("Change to T21 Whiteside RU1600 1/8 in upcut", prompts.get(0).title());
        assertTrue(prompts.get(0).message().startsWith("Change to T21 Whiteside RU1600 1/8 in upcut."));
        assertEquals(List.of(UserResponse.CONTINUE, UserResponse.ABORT), prompts.get(0).options());

        assertEquals("T21", res.tool());
        assertEquals(-68.5, res.triggerZ(), 0);
        assertEquals(-60.0, res.wcoZBefore(), 0);
        assertEquals(-58.5, res.wcoZ(), 0);
        assertEquals(-58.5, m.g54[2], 0);
        // D10: back over the work at safe Z, nowhere lower than the probe contact
        assertEquals(-150.0, m.mpos[0], 0);
        assertEquals(-300.0, m.mpos[1], 0);
        assertEquals(-5.0, m.mpos[2], 0);
        assertFalse(m.spindleOn);

        assertEquals(List.of("change_requested", "confirmed", "measured"), events.toolEvents());
        var measured = events.tools.get(2);
        assertEquals(-68.5, measured.get("trigger_mz").getAsDouble(), 0);
        assertEquals(10.0, measured.get("ref").getAsDouble(), 0);
        assertEquals(-58.5, measured.get("wco_z").getAsDouble(), 0);
        assertEquals(-60.0, measured.get("wco_z_before").getAsDouble(), 0);
        assertEquals(line("T21 M6"), events.tools.get(0).get("file_line").getAsInt());
        assertEquals(List.of("g10|T21|BitSetter tool change to T21: S=-68.500, ref=10.000"), events.expects);
    }

    @Test
    public void toolFromTheJobWhenTheM6LineHasNoToolWord() throws Exception {
        String program = Fixtures.TWO_TOOLS.replace("T21 M6", "T21\nM6");
        JobModel j = JobModel.parse("split.nc", program, null);
        int m6 = -1;
        for (int i = 1; i <= j.lineCount(); i++) {
            if (j.line(i).trim().equals("M6") && j.toolChangeTarget(i) == 21) {
                m6 = i;
            }
        }
        assertTrue(m6 > 0);
        routine().toolChange("M6", m6, j, 10.0);
        assertEquals("M5", m.log.get(0)); // no tool word to repeat
        assertEquals("Change to T21 Whiteside RU1600 1/8 in upcut", prompts.get(0).title());
    }

    @Test
    public void abortAtThePromptLeavesTheMachineParkedAtSafeZWithTheSpindleOff() throws Exception {
        try {
            routine(UserResponse.ABORT).toolChange("T21 M6", line("T21 M6"), job, 10.0);
            fail("expected abort");
        } catch (InterceptAbortedException expected) {
            // the service cancels the stream
        }
        assertEquals(List.of("T21", "M5", "G21 G90", "G53 G0 Z-5.000", "G53 G0 X-222.000 Y-440.000", "G4 P0"), m.log);
        assertFalse(m.spindleOn);
        assertEquals(-5.0, m.mpos[2], 0);
        assertEquals(-60.0, m.g54[2], 0); // Z offset untouched
        assertEquals(List.of("change_requested"), events.toolEvents());
    }

    @Test
    public void withoutAReferenceItParksExplainsAndFailsLeavingTheStreamPaused() throws Exception {
        try {
            routine().toolChange("T21 M6", line("T21 M6"), job, null);
            fail("expected failure");
        } catch (ToolChangeRoutine.RoutineException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("No BitSetter reference"));
        }
        // the operator is told why in the UGS dialog, after parking; nothing is measured
        assertEquals(List.of(ToolChangeRoutine.STEP_NO_REFERENCE), prompts.stream().map(InterceptorPrompt::stepId).toList());
        assertEquals("No BitSetter reference", prompts.get(0).title());
        assertTrue(prompts.get(0).message().contains("parked at safe Z"));
        assertEquals(6, sentAtPrompt.get(0).size());
        assertEquals("G4 P0", m.log.get(m.log.size() - 1));
        assertTrue(m.log.stream().noneMatch(l -> l.startsWith("G38.2") || l.startsWith("G10")));
        assertFalse(m.spindleOn);
        assertEquals(-5.0, m.mpos[2], 0);
        assertEquals(List.of("change_requested"), events.toolEvents());
    }

    @Test
    public void withoutAReferenceTheOperatorCanAbortTheJob() throws Exception {
        try {
            routine(UserResponse.ABORT).toolChange("T21 M6", line("T21 M6"), job, null);
            fail("expected abort");
        } catch (InterceptAbortedException expected) {
            // the service cancels the stream; the machine stays parked
        }
        assertEquals("G4 P0", m.log.get(m.log.size() - 1));
        assertFalse(m.spindleOn);
    }

    // ---------------------------------------------------------------- D22: same tool, length known

    @Test
    public void sameToolWithKnownLengthSendsOnlyTheToolWord() throws Exception {
        ToolChangeRoutine r = routine();
        ToolChangeRoutine.Result res = r.toolChange("T21 M6", line("T21 M6"), job, 10.0, "T21");
        assertEquals(List.of("T21"), m.log);
        assertEquals(m.log, r.sent());
        assertTrue(prompts.isEmpty());
        assertFalse(res.measured());
        assertEquals("T21", res.tool());
        assertNull(res.wcoZ());
        assertEquals(-60.0, m.g54[2], 0); // Z offset untouched
        assertEquals(List.of("confirmed"), events.toolEvents());
        var d = events.tools.get(0);
        assertTrue(d.get("measurement_skipped").getAsBoolean());
        assertEquals(ToolChangeRoutine.NOTE_SKIPPED, d.get("note").getAsString());
        assertEquals(line("T21 M6"), d.get("file_line").getAsInt());
        assertTrue(events.expects.isEmpty());
    }

    @Test
    public void knownLengthToolIsComparedByNumber() throws Exception {
        routine().toolChange("M06 T2", -1, null, 10.0, "T02");
        assertEquals(List.of("T2"), m.log);
        assertTrue(prompts.isEmpty());
    }

    @Test
    public void sameToolFromTheJobWithoutAToolWordSendsNothing() throws Exception {
        String program = Fixtures.TWO_TOOLS.replace("T21 M6", "T21\nM6");
        JobModel j = JobModel.parse("split.nc", program, null);
        int m6 = -1;
        for (int i = 1; i <= j.lineCount(); i++) {
            if (j.line(i).trim().equals("M6") && j.toolChangeTarget(i) == 21) {
                m6 = i;
            }
        }
        ToolChangeRoutine.Result res = routine().toolChange("M6", m6, j, 10.0, "T21");
        assertTrue(m.log.isEmpty());
        assertFalse(res.measured());
        assertTrue(prompts.isEmpty());
    }

    @Test
    public void anyDoubtRunsTheFullSequence() throws Exception {
        // other tool known, no known tool, M6 without a resolvable tool number
        String[][] cases = {{"T21 M6", "T02"}, {"T21 M6", null}, {"M6", "T21"}};
        for (String[] c : cases) {
            FakeMachine fm = new FakeMachine();
            List<InterceptorPrompt> asked = new ArrayList<>();
            ToolChangeRoutine r = new ToolChangeRoutine(Fixtures.profile(), fm, p -> {
                asked.add(p);
                return UserResponse.CONTINUE;
            }, new Fixtures.Events());
            ToolChangeRoutine.Result res = r.toolChange(c[0], c[0].startsWith("T") ? line(c[0]) : -1,
                    c[0].startsWith("T") ? job : null, 10.0, c[1]);
            String which = c[0] + " / " + c[1];
            assertTrue(which, res.measured());
            assertEquals(which, ToolChangeRoutine.STEP_CHANGE, asked.get(0).stepId());
            assertTrue(which, fm.log.contains("M5"));
            assertTrue(which, fm.log.stream().anyMatch(l -> l.startsWith("G38.2")));
            assertTrue(which, fm.log.stream().anyMatch(l -> l.startsWith("G10 L2 P1 Z")));
        }
    }

    @Test
    public void activeProbePinPromptsAgainThenMeasures() throws Exception {
        m.pins.add(true);   // still pressed after the change
        m.pins.add(false);
        routine().toolChange("T21 M6", line("T21 M6"), job, 10.0);
        assertEquals(List.of(ToolChangeRoutine.STEP_CHANGE, ToolChangeRoutine.STEP_PROBE_PIN),
                prompts.stream().map(InterceptorPrompt::stepId).toList());
        assertTrue(m.log.contains("G10 L2 P1 Z-60.000"));
    }

    @Test
    public void probePinThatStaysActiveFailsWithoutProbing() throws Exception {
        m.pinDefault = true;
        try {
            routine().toolChange("T21 M6", line("T21 M6"), job, 10.0);
            fail("expected failure");
        } catch (ToolChangeRoutine.RoutineException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("stays active"));
        }
        assertTrue(m.log.stream().noneMatch(l -> l.startsWith("G38.2")));
        assertTrue(m.log.stream().noneMatch(l -> l.startsWith("G10")));
        assertEquals(List.of("G90", "G53 G0 Z-5.000"), m.log.subList(m.log.size() - 2, m.log.size()));
    }

    @Test
    public void probeWithoutContactRetreatsAndFails() throws Exception {
        m.probeNoContact = true;
        try {
            routine().toolChange("T21 M6", line("T21 M6"), job, 10.0);
            fail("expected failure");
        } catch (ToolChangeRoutine.RoutineException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("no contact"));
        }
        assertTrue(m.log.stream().noneMatch(l -> l.startsWith("G10")));
        assertEquals(List.of("G90", "G53 G0 Z-5.000"), m.log.subList(m.log.size() - 2, m.log.size()));
    }

    @Test
    public void readBackMismatchFails() throws Exception {
        m.g10ZError = 0.02;
        try {
            routine().toolChange("T21 M6", line("T21 M6"), job, 10.0);
            fail("expected failure");
        } catch (ToolChangeRoutine.RoutineException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("G54 Z reads"));
        }
        assertFalse(m.log.contains("G53 G0 X-150.000 Y-300.000")); // no return over the work
        assertEquals(-5.0, m.mpos[2], 0);
    }

    @Test
    public void envelopeZIsCheckedWithThePostMeasurementOffset() throws Exception {
        m.triggerZ = -68.5;
        try {
            // ref -30 → WCO_z = -98.5: the op's Z-1 would be machine -99.5, below the -95 envelope
            routine().toolChange("T21 M6", line("T21 M6"), job, -30.0);
            fail("expected failure");
        } catch (ToolChangeRoutine.RoutineException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("envelope"));
        }
        assertTrue(m.log.stream().noneMatch(l -> l.startsWith("G10")));
    }

    @Test
    public void referenceMeasurementStoresWcoMinusTrigger() throws Exception {
        m.g54[2] = -55.6;
        m.triggerZ = -64.120000000000005;
        ToolChangeRoutine r = routine();
        ToolChangeRoutine.Reference ref = r.measureReference("T02", true);
        assertEquals(8.52, ref.ref(), 0); // -55.6 - -64.12, rounded (J-2)
        assertEquals(-64.12, ref.triggerZ(), 0);
        assertEquals(-64.12, ref.repeatTriggerZ(), 0);
        assertTrue(prompts.isEmpty());
        assertEquals(List.of("reference_set"), events.toolEvents());
        assertEquals("$#", m.log.get(0));
        assertEquals("M5", m.log.get(1));
        assertEquals(2, m.log.stream().filter(l -> l.startsWith("G38.2 Z-3.000")).count());
        assertEquals(List.of("G53 G0 X-150.000 Y-300.000", "G4 P0"), m.log.subList(m.log.size() - 2, m.log.size()));
    }

    @Test
    public void toolWordParsing() {
        assertEquals(21, ToolChangeRoutine.toolNumber("T21 M6"));
        assertEquals(2, ToolChangeRoutine.toolNumber("M6T02"));
        assertEquals(-1, ToolChangeRoutine.toolNumber("M6 (T5 next)"));
        assertNull(null);
    }
}
