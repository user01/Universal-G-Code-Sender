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
package com.willwinder.ugs.nbp.bridge.api;

import com.willwinder.ugs.nbp.bridge.api.Classifier.LineClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** api.md "Authority classification", mirrored from ugs_mcp.safety (bridge/mcp/tests/test_safety.py). */
public class ClassifierTest {

    private static void is(String line, LineClass cls, Level level) {
        Classifier.Classification c = Classifier.classify(line);
        assertEquals(line, cls, c.cls());
        assertEquals(line, level, c.required());
    }

    @Test
    public void queries() {
        for (String q : new String[]{"?", "$$", "$#", "$G", "$I", "$N", " $g ", "$$ (all settings)", "$# ; offsets"}) {
            is(q, LineClass.QUERY, Level.QUERY);
        }
        is("(just a comment)", LineClass.QUERY, Level.QUERY);
    }

    @Test
    public void homingAndUnlockNeedMotionAndTheOperator() {
        is("$H", LineClass.CONFIRM, Level.MOTION);
        is("$x", LineClass.CONFIRM, Level.MOTION);
        assertTrue(Classifier.classify("$H").needsOperator());
        assertFalse(Classifier.classify("G0 X1").needsOperator());
    }

    @Test
    public void everythingElseIsMotion() {
        for (String m : new String[]{"G0 X1", "g91 g0 z5", "$J=G91 X1 F100", "G10 L2 P1 X0", "M3 S1000", "$C", "$130=445",
                "$RST=*", "$N0=G54", "T2 M6", "G38.2 Z-10 F100", "$SLP"}) {
            is(m, LineClass.MOTION, Level.MOTION);
        }
    }

    @Test
    public void normalizeDropsCommentsAndSpaces() {
        assertEquals("G0X1", Classifier.normalize("g0 x1 (move) ; rest"));
        assertEquals("$$", Classifier.normalize(" $$ "));
        assertEquals("G1X2", Classifier.normalize("G1 (a) X2"));
    }

    @Test
    public void realtimeCharactersAndLineBreaksAreRefused() {
        assertNull(Classifier.invalidReason("?"));
        assertNull(Classifier.invalidReason("G0 X1 (fine)"));
        assertNotNull(Classifier.invalidReason("G0 X1 !"));
        assertNotNull(Classifier.invalidReason("(what?)"));
        assertNotNull(Classifier.invalidReason("G0 X1~"));
        assertNotNull(Classifier.invalidReason("G0\nG1"));
        assertNotNull(Classifier.invalidReason("G0 \u0018"));
        assertNotNull(Classifier.invalidReason("G0 X1 é"));
        assertNotNull(Classifier.invalidReason("G0 " + "X1 ".repeat(100)));
    }

    @Test
    public void levelsAreOrdered() {
        assertTrue(Level.STREAM.allows(Level.MOTION));
        assertTrue(Level.MOTION.allows(Level.QUERY));
        assertFalse(Level.QUERY.allows(Level.MOTION));
        assertFalse(Level.OBSERVE.allows(Level.QUERY));
        assertTrue(Level.MOTION.isArmed());
        assertFalse(Level.QUERY.isArmed());
        assertEquals(Level.MOTION, Level.parse("motion"));
    }
}
