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
 * One operation of a job (SPEC §6.3, §11.6).
 *
 * @param index      1-based position in the job
 * @param name       e.g. {@code clear_hi}
 * @param tool       tool id as written by the post or sidecar, e.g. {@code T21} (null if unknown)
 * @param toolNumber the T number (0 if unknown)
 * @param startLine  first file line (1-based; for header-derived ops the {@code (---- OP ...)} comment)
 * @param endLine    last file line, inclusive
 * @param plungeFeed plunge feed in mm/min, null if unknown
 * @param cutFeed    cutting feed in mm/min, null if unknown
 * @param source     {@code sidecar}, {@code header}, {@code freecad} or {@code tool_change}
 */
public record Operation(int index, String name, String tool, int toolNumber, int startLine, int endLine,
                        Double plungeFeed, Double cutFeed, String source) {

    public boolean contains(int line) {
        return line >= startLine && line <= endLine;
    }
}
