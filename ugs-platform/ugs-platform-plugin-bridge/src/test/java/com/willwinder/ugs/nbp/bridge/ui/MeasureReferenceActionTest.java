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
package com.willwinder.ugs.nbp.bridge.ui;

import com.willwinder.ugs.nbp.bridge.runtime.ToolChangeRoutine;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MeasureReferenceActionTest {

    @Test
    public void toolNumbersAreNormalized() {
        assertEquals("T02", MeasureReferenceAction.normalizeTool("T02"));
        assertEquals("T02", MeasureReferenceAction.normalizeTool(" t2 "));
        assertEquals("T21", MeasureReferenceAction.normalizeTool("21"));
        assertEquals("T101", MeasureReferenceAction.normalizeTool("T101"));
        assertNull(MeasureReferenceAction.normalizeTool(""));
        assertNull(MeasureReferenceAction.normalizeTool("endmill"));
        assertNull(MeasureReferenceAction.normalizeTool("T"));
    }

    @Test
    public void resultShowsTriggerRepeatOffsetAndReference() {
        String s = MeasureReferenceAction.describe(new ToolChangeRoutine.Reference("T02", -71.234, -40.5, 30.734, -71.25));
        assertTrue(s, s.contains("Tool T02"));
        assertTrue(s, s.contains("machine Z -71.234"));
        assertTrue(s, s.contains("machine Z -71.250 (difference 0.016 mm)"));
        assertTrue(s, s.contains("(G54): -40.500"));
        assertTrue(s, s.contains("trigger): 30.734"));
        String once = MeasureReferenceAction.describe(new ToolChangeRoutine.Reference("T02", -71.234, -40.5, 30.734, null));
        assertFalse(once, once.contains("Repeat"));
    }
}
