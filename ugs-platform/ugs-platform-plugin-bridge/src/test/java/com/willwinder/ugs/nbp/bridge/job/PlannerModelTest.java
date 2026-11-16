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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class PlannerModelTest {

    @Test
    public void theBundledShapeokoProfileSaysFourteenBlocks() {
        PlannerModel p = PlannerModel.forProfile(com.willwinder.ugs.nbp.bridge.runtime.JobRuntime.loadProfile(null));
        assertEquals(14, p.plannerBlocks());
        assertEquals(128, p.rxBufferBytes());
    }

    @Test
    public void aProfileWithoutPlannerFieldsFallsBackToStockGrbl() {
        assertEquals(PlannerModel.GRBL, PlannerModel.forProfile(new MachineProfile()));
        assertEquals(PlannerModel.GRBL, PlannerModel.forProfile(null));
    }

    @Test
    public void optFromDollarIWins() {
        PlannerModel base = PlannerModel.GRBL;
        PlannerModel p = PlannerModel.fromOpt("[OPT:VNPR,14,128]", base);
        assertEquals(14, p.plannerBlocks());
        assertEquals(128, p.rxBufferBytes());
        assertEquals(base.positionToleranceMm(), p.positionToleranceMm(), 0);
        // as UGS sometimes logs the whole $I reply in one message
        assertEquals(14, PlannerModel.fromOpt("[VER:1.1f.20170131:]\n[OPT:VNPR,14,128]\nok", base).plannerBlocks());
        assertEquals(35, PlannerModel.fromOpt("[OPT:VL,35,1024]", base).plannerBlocks());
    }

    @Test
    public void noOrBadOptKeepsTheBase() {
        PlannerModel base = PlannerModel.GRBL;
        assertSame(base, PlannerModel.fromOpt(null, base));
        assertSame(base, PlannerModel.fromOpt("[VER:1.1f.20170131:]", base));
        assertSame(base, PlannerModel.fromOpt("[OPT:]", base));
        assertSame(base, PlannerModel.fromOpt("[OPT:V,0,128]", base));
        assertSame(base, PlannerModel.fromOpt("[OPT:V,15,2]", base));
    }
}
