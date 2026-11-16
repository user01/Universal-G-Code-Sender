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

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** W2 follow-ups to J1: J-1 (BitZero corner, envelope Z after measuring), J-2 (rounding), D15 (file dwell). */
public class FollowUpsTest {

    private static MachineProfile profileWithBore(Double bore, double[] corner) {
        MachineProfile p = Programs.profile();
        p.bitzero.boreDiameter = bore;
        p.bitzero.boreCenterToCorner = corner;
        return p;
    }

    @Test
    public void bitzeroCornerRefusesUntilTheBoreIsInTheProfile() throws Exception {
        ProbeSequences.CornerSequence s = ProbeSequences.bitzeroCorner(Programs.profile(), 6.35);
        assertTrue(s.refused());
        assertTrue(s.refusal(), s.refusal().contains("bore_diameter") && s.refusal().contains("B3"));
        assertNull(s.x());

        // the shipped profile has null bore values too
        Path shipped = Path.of("../../bridge/profiles/shapeoko4-standard.json");
        if (Files.exists(shipped)) {
            MachineProfile real = MachineProfile.read(shipped);
            assertNull(real.bitzero.boreDiameter);
            assertTrue(ProbeSequences.bitzeroCorner(real, 6.35).refused());
            assertEquals(Boolean.TRUE, real.spindle.preferFileDwell);
        }
        assertTrue(ProbeSequences.bitzeroCorner(profileWithBore(12.0, new double[]{-20, -20}), 12.0).refused());
    }

    @Test
    public void bitzeroCornerWithSyntheticBore() {
        // synthetic values (ledger B3 is not measured): 12 mm bore, corner 20 mm left and in front of its center
        ProbeSequences.CornerSequence s = ProbeSequences.bitzeroCorner(profileWithBore(12.0, new double[]{-20, -20}), 6.35);
        assertFalse(s.refused());
        assertEquals(List.of(
                "G21 G91",
                "G38.2 X-6.650 F200",
                "G0 X2.000",
                "G38.2 X-3.000 F50",
                "G0 X2.000",
                "G38.2 X6.650 F200",
                "G0 X-2.000",
                "G38.2 X3.000 F50",
                "G0 X-2.000",
                "G90"), s.x().commands());
        assertEquals(3, s.x().slowMinusIndex());
        assertEquals(7, s.x().slowPlusIndex());
        assertEquals("G38.2 Y-6.650 F200", s.y().commands().get(1));
        // contacts: bore 12, tool 6.35 → walls at center ± 2.825
        double cx = -150, cy = -300;
        double xm = cx - 2.825, xp = cx + 2.825;
        assertEquals("G53 G0 X-150.000", ProbeSequences.CornerSequence.centerMove("X", xm, xp));
        assertNull(s.boreCheck("X", xm, xp, 0.2));
        assertNotNull(s.boreCheck("X", xm, xp + 1, 0.2));
        assertEquals("G10 L2 P1 X-170.000 Y-320.000", s.setOrigin("G54", cx, cy));
        // every probe move is bounded by the free space in the bore + 1 mm
        for (String c : s.x().commands()) {
            if (c.startsWith("G38.2")) {
                double d = Math.abs(Double.parseDouble(c.replaceAll("^G38.2 X(-?[0-9.]+).*$", "$1")));
                assertTrue(c, d <= 12.0 - 6.35 + 1.0 + 1e-9);
            }
        }
    }

    @Test
    public void storedReferenceAndOffsetsAreRoundedToAMicron() {
        assertEquals(-7.52, BitSetter.reference(-55.6, -48.079999999999997), 0);
        assertEquals(-58.5, BitSetter.wcoZForTool(-68.50000000001, 10.0), 0);
        assertEquals(-7.52, BitSetter.round3(-7.520000000000003), 0);
        assertEquals("[-1.0, 2.346]", java.util.Arrays.toString(BitSetter.round3(new double[]{-0.99999999, 2.3456})));
    }

    @Test
    public void resumeDwellUsesTheFilesOwnG4AfterM3() {
        String program = Programs.TWO_TOOLS.replace("S18000 M3\n(---- OP 02", "S18000 M3\nG4 P2.5\n(---- OP 02");
        JobModel job = JobModel.parse("dwell.nc", program, null);
        MachineProfile profile = Programs.profile(); // spin_up_s 3.0
        ResumeContext ctx = ResumeContext.homedWith(Programs.WCO, "T21");
        ResumePlan p = ResumePlanner.plan(job, profile, null, ctx, ResumeTarget.operation("finish"));
        assertTrue(p.preamble.toString(), p.preamble.contains("G4 P2.5"));
        assertEquals("file", p.spinUpSource);

        // the first op has no G4 after its M3 → profile value
        ResumePlan first = ResumePlanner.plan(job, profile, null, ResumeContext.homedWith(Programs.WCO, "T02"),
                ResumeTarget.operation("pocket"));
        assertTrue(first.preamble.toString(), first.preamble.contains("G4 P3"));
        assertEquals("profile", first.spinUpSource);

        // prefer_file_dwell false → profile value
        profile.spindle.preferFileDwell = false;
        ResumePlan forced = ResumePlanner.plan(job, profile, null, ctx, ResumeTarget.operation("finish"));
        assertTrue(forced.preamble.toString(), forced.preamble.contains("G4 P3"));
        assertEquals(2.5, ResumePlanner.fileSpinUpDwell(job, job.findOperation("finish").get().startLine() + 2), 0);
    }

    @Test
    public void envelopeZWaitsForTheMeasurementWhenTheToolIsMeasured() {
        JobModel job = JobModel.parse("two.nc", Programs.TWO_TOOLS, null);
        MachineProfile profile = Programs.profile();
        // a WCO whose Z would put the cut below the envelope with the *old* tool length
        double[] wco = {-300, -350, -94.5};
        ResumeContext ctx = ResumeContext.homedWith(wco, "T21");
        ctx.toolLengthKnown = false; // → tool_measure
        ResumePlan p = ResumePlanner.plan(job, profile, null, ctx, ResumeTarget.operation("finish"));
        ResumePlan.Check env = p.checks.stream().filter(c -> c.name.equals("envelope")).findFirst().orElseThrow();
        assertTrue(env.detail, env.ok);
        assertTrue(env.detail, env.detail.contains("Z is checked after the tool is measured"));

        ResumeContext known = ResumeContext.homedWith(wco, "T21");
        ResumePlan q = ResumePlanner.plan(job, profile, null, known, ResumeTarget.operation("finish"));
        ResumePlan.Check env2 = q.checks.stream().filter(c -> c.name.equals("envelope")).findFirst().orElseThrow();
        assertFalse(env2.detail, env2.ok);
        assertTrue(env2.detail, env2.detail.startsWith("Z "));
    }
}
