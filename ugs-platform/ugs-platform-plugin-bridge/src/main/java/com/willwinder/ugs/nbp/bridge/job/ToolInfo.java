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
 * A tool of the job's tool table. Feeds are mm/min (as written by the post), null if unknown.
 *
 * @param id     as written, e.g. {@code T02}
 * @param number the T number
 */
public record ToolInfo(String id, int number, String name, Double diameter, Double cutFeed, Double plungeFeed) {

    public static String canonicalId(int number) {
        return "T" + number;
    }

    /** Parses {@code T02}, {@code t2}, {@code 2} to 2; returns -1 when it isn't a tool id. */
    public static int parseNumber(String id) {
        if (id == null) {
            return -1;
        }
        String s = id.trim();
        if (s.startsWith("T") || s.startsWith("t")) {
            s = s.substring(1);
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    ToolInfo merge(ToolInfo o) {
        return new ToolInfo(id != null ? id : o.id, number,
                name != null ? name : o.name,
                diameter != null ? diameter : o.diameter,
                cutFeed != null ? cutFeed : o.cutFeed,
                plungeFeed != null ? plungeFeed : o.plungeFeed);
    }
}
