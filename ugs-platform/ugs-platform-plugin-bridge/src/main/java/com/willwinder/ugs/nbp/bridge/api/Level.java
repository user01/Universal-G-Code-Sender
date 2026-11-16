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

import java.util.Locale;

/**
 * Claude's authority levels (SPEC §4.3), lowest first. Each level includes everything below it.
 */
public enum Level {
    OBSERVE, QUERY, MOTION, STREAM;

    /** The name used in the API and the log ({@code observe}, {@code query}, ...). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean allows(Level required) {
        return ordinal() >= required.ordinal();
    }

    /** True for the armed levels, which expire (Motion, Stream). */
    public boolean isArmed() {
        return this == MOTION || this == STREAM;
    }

    public static Level parse(String id) {
        return valueOf(id.trim().toUpperCase(Locale.ROOT));
    }

    public String label() {
        return switch (this) {
            case OBSERVE -> "Observe";
            case QUERY -> "Query";
            case MOTION -> "Motion";
            case STREAM -> "Stream";
        };
    }
}
