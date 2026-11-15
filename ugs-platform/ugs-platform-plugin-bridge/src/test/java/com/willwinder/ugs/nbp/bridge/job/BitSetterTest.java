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

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BitSetterTest {
    private final MachineProfile profile = Programs.profile();

    @Test
    public void referenceAndToolChange() {
        // Tool A zeroed on the stock: WCO_z = -60.0; A triggers the BitSetter at machine Z -91.25
        double ref = BitSetter.reference(-60.0, -91.25);
        assertEquals(31.25, ref, 1e-9);
        // Tool B is 4.5 mm longer: triggers 4.5 mm higher
        double wcoB = BitSetter.wcoZForTool(-86.75, ref);
        assertEquals(-55.5, wcoB, 1e-9);
        // B's tip at work Z0 is machine Z -55.5, i.e. 4.5 mm above A's tip at Z0: same stock top
        assertEquals("G10 L2 P1 Z-55.500", BitSetter.setWcoZCommand("G54", wcoB));
        assertEquals("G10 L2 P2 Z1.000", BitSetter.setWcoZCommand("G55", 1));
        // Round trip: re-measuring A gives back A's WCO
        assertEquals(-60.0, BitSetter.wcoZForTool(-91.25, ref), 1e-9);
    }

    @Test
    public void repeatability() {
        assertTrue(BitSetter.verifyRepeat(-91.25, -91.29, profile.repeatTolerance()).ok());
        assertTrue(BitSetter.verifyRepeat(-91.25, -91.30, 0.05).ok());
        BitSetter.Repeat bad = BitSetter.verifyRepeat(-91.25, -91.31, 0.05);
        assertFalse(bad.ok());
        assertEquals(0.06, bad.difference(), 1e-9);
    }

    @Test
    public void invalidation() {
        BitSetter.Reference r = new BitSetter.Reference(31.25, -91.25, "T02", new double[]{-4, -449}, "h1");
        assertTrue(BitSetter.invalidationReasons(r, profile, "h1", false).isEmpty());
        assertEquals(1, BitSetter.invalidationReasons(r, profile, "h2", false).size());
        assertEquals(1, BitSetter.invalidationReasons(r, profile, "h1", true).size());
        MachineProfile moved = Programs.profile();
        moved.bitsetter.xy = new double[]{-5, -449};
        List<String> reasons = BitSetter.invalidationReasons(r, moved, "h1", false);
        assertEquals(1, reasons.size());
        assertTrue(reasons.get(0).contains("BitSetter position"));
        assertEquals(List.of("no reference measured"), BitSetter.invalidationReasons(null, profile, "h1", false));
        // unknown homing signature at measurement time: not a reason by itself
        BitSetter.Reference noSig = new BitSetter.Reference(31.25, -91.25, "T02", new double[]{-4, -449}, null);
        assertTrue(BitSetter.invalidationReasons(noSig, profile, "h9", false).isEmpty());
    }

    @Test
    public void bitsetterSequenceIsTwoPassAndBounded() {
        ProbeSequences.ProbeSequence s = ProbeSequences.bitsetterMeasure(profile);
        assertEquals(List.of(
                "G21 G90",
                "G53 G0 Z-5.000",
                "G53 G0 X-4.000 Y-449.000",
                "G91",
                "G38.2 Z-90.000 F800",
                "G0 Z2.000",
                "G38.2 Z-3.000 F100",
                "G90",
                "G53 G0 Z-5.000"), s.commands());
        assertEquals(4, s.fastProbeIndex());
        assertEquals(6, s.slowProbeIndex());
        assertEquals("P", s.probePin());
        assertTrue(s.warnings().isEmpty());

        // travel is bounded by the envelope floor too
        MachineProfile shallow = Programs.profile();
        shallow.envelopeMm.min[2] = -50;
        shallow.bitsetter.startZMachine = -10.0;
        ProbeSequences.ProbeSequence t = ProbeSequences.bitsetterMeasure(shallow);
        assertTrue(t.commands().contains("G53 G0 Z-10.000"));
        assertEquals("G38.2 Z-40.000 F800", t.commands().get(t.fastProbeIndex()));
    }

    @Test
    public void bitsetterOutsideEnvelopeIsRefused() {
        MachineProfile bad = Programs.profile();
        bad.bitsetter.xy = new double[]{10, -449};
        try {
            ProbeSequences.bitsetterMeasure(bad);
            fail();
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("envelope"));
        }
    }

    @Test
    public void bitzeroNeedsPlateThickness() {
        try {
            ProbeSequences.bitzeroZ(profile, "G54");
            fail();
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("plate thickness"));
        }
        MachineProfile p = Programs.profile();
        p.bitzero.plateThickness = 15.0;
        ProbeSequences.ProbeSequence s = ProbeSequences.bitzeroZ(p, "G54");
        assertEquals(List.of(
                "G21 G91",
                "G38.2 Z-25.000 F200",
                "G0 Z2.000",
                "G38.2 Z-3.000 F50",
                "G10 L20 P1 Z15.000",
                "G0 Z2.000",
                "G90"), s.commands());
        assertEquals(1, s.fastProbeIndex());
        assertEquals(3, s.slowProbeIndex());
        assertFalse(s.warnings().isEmpty()); // thickness not verified
    }

    @Test
    public void realProfileLoads() throws Exception {
        MachineProfile real = TestFiles.profile();
        assertEquals(-5.0, real.safeZ(), 1e-9);
        assertEquals(2.0, real.spinUpSeconds(), 1e-9);
        assertEquals(0.05, real.repeatTolerance(), 1e-9);
        ProbeSequences.ProbeSequence s = ProbeSequences.bitsetterMeasure(real);
        assertFalse(s.warnings().isEmpty()); // position not verified yet
    }
}
