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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Controller buffering assumptions used to decide which acknowledged lines may not have executed (SPEC §11.6).
 *
 * @param plannerBlocks        motion blocks the controller's planner holds (GRBL 1.1: 15 usable of 16)
 * @param rxBufferBytes        serial RX buffer (GRBL: 128 bytes)
 * @param ackMeansSent         true if the journal's {@code line_acked} may count lines that were only sent (sitting
 *                             unparsed in the RX buffer) rather than answered {@code ok}; then the RX buffer is
 *                             backed over too
 * @param positionToleranceMm  max distance between the reported position and a path segment to call it a match
 */
public record PlannerModel(int plannerBlocks, int rxBufferBytes, boolean ackMeansSent, double positionToleranceMm) {

    /** Stock GRBL 1.1 ({@code [OPT:…,15,128]}). The Carbide 3D build on the Shapeoko reports 14 blocks. */
    public static final PlannerModel GRBL = new PlannerModel(15, 128, false, 0.05);

    /** {@code [OPT:<codes>,<planner blocks>,<rx buffer bytes>]} from {@code $I}. */
    private static final Pattern OPT = Pattern.compile("\\[OPT:[^,\\]]*,(\\d+),(\\d+)");

    /**
     * The planner as the controller reports it in {@code $I}: {@code base} with the block count and RX buffer
     * size from an {@code [OPT:…]} line, or {@code base} unchanged when the text has none (or nonsense values).
     */
    public static PlannerModel fromOpt(String text, PlannerModel base) {
        if (text == null) {
            return base;
        }
        Matcher m = OPT.matcher(text);
        if (!m.find()) {
            return base;
        }
        try {
            int blocks = Integer.parseInt(m.group(1));
            int rx = Integer.parseInt(m.group(2));
            if (blocks < 1 || blocks > 64 || rx < 16 || rx > 4096) {
                return base;
            }
            return new PlannerModel(blocks, rx, base.ackMeansSent(), base.positionToleranceMm());
        } catch (NumberFormatException e) {
            return base;
        }
    }

    /** The profile's {@code planner_blocks} / {@code rx_buffer_bytes} over {@link #GRBL}. */
    public static PlannerModel forProfile(MachineProfile p) {
        if (p == null) {
            return GRBL;
        }
        int blocks = p.plannerBlocks != null && p.plannerBlocks > 0 ? p.plannerBlocks : GRBL.plannerBlocks();
        int rx = p.rxBufferBytes != null && p.rxBufferBytes > 0 ? p.rxBufferBytes : GRBL.rxBufferBytes();
        return new PlannerModel(blocks, rx, GRBL.ackMeansSent(), GRBL.positionToleranceMm());
    }

    public PlannerModel withTolerance(double tolMm) {
        return new PlannerModel(plannerBlocks, rxBufferBytes, ackMeansSent, tolMm);
    }

    public PlannerModel withAckMeansSent(boolean v) {
        return new PlannerModel(plannerBlocks, rxBufferBytes, v, positionToleranceMm);
    }
}
