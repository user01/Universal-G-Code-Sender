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

/**
 * What the bridge knows about the machine right now, as input to {@link ResumePlanner}. Plain data; the caller
 * fills it from the controller state. Nothing here talks to the machine.
 */
public class ResumeContext {
    /** Homed in this connection. */
    public boolean homed;
    /** Current WCO in mm (from {@code $#}/status), null if unknown. */
    public double[] currentWco;
    /** Tool the operator says is in the collet (e.g. {@code T21}); null = use the journal's, else unknown. */
    public String currentTool;
    /** False when the length of the tool in the collet hasn't been measured since the last reset/power-up. */
    public boolean toolLengthKnown = true;
    /** The controller lost power or the stop was a crash/power cut: tool may have been touched. */
    public boolean powerLoss;
    /** BitSetter reference ({@link BitSetter}), null = use the journal's. */
    public Double bitsetterRef;
    /** Resume exactly at the computed line even if it isn't a safe point (needs operator confirmation). */
    public boolean midContour;
    /** Buffering assumptions for the "stopped" target. */
    public PlannerModel planner = PlannerModel.GRBL;
    /** WCO comparison tolerance, mm (SPEC §11.3: 0.01). */
    public double wcoTolerance = 0.01;

    public static ResumeContext homedWith(double[] wco, String tool) {
        ResumeContext c = new ResumeContext();
        c.homed = true;
        c.currentWco = wco;
        c.currentTool = tool;
        return c;
    }
}
