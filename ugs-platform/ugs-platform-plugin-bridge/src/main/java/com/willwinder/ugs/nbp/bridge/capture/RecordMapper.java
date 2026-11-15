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
package com.willwinder.ugs.nbp.bridge.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.willwinder.universalgcodesender.listeners.AccessoryStates;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.ControllerStatus;
import com.willwinder.universalgcodesender.listeners.EnabledPins;
import com.willwinder.universalgcodesender.listeners.OverridePercents;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps UGS objects to the {@code data} payloads of bridge/schema/log-record.schema.json.
 */
public final class RecordMapper {
    private static final Pattern ERROR = Pattern.compile("^error:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALARM = Pattern.compile("^ALARM:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private RecordMapper() {
    }

    /** UGS controller state to the schema's state names (GRBL spelling). */
    public static String stateName(ControllerState s) {
        if (s == null) {
            return "Unknown";
        }
        return switch (s) {
            case IDLE -> "Idle";
            case RUN -> "Run";
            case HOLD -> "Hold";
            case JOG -> "Jog";
            case ALARM -> "Alarm";
            case DOOR -> "Door";
            case CHECK -> "Check";
            case HOME -> "Home";
            case SLEEP -> "Sleep";
            case TOOL -> "Tool";
            default -> "Unknown";
        };
    }

    /**
     * Maps a UGS {@link ControllerStatus} (used when the firmware doesn't send GRBL 1.1 {@code <...>} reports).
     * Positions are converted to mm; {@code raw} is empty because UGS doesn't keep the original report.
     */
    public static JsonObject status(ControllerStatus s) {
        JsonObject o = new JsonObject();
        o.addProperty("state", stateName(s.getState()));
        o.add("substate", parseIntOrNull(s.getSubState()));
        o.add("mpos", position(s.getMachineCoord()));
        o.add("wpos", position(s.getWorkCoord()));
        o.add("wco", position(s.getWorkCoordinateOffset()));
        o.add("feed", number(s.getFeedSpeed()));
        o.add("spindle", number(s.getSpindleSpeed()));
        OverridePercents ov = s.getOverrides();
        if (ov != null) {
            JsonObject j = new JsonObject();
            j.addProperty("feed", ov.feed());
            j.addProperty("rapid", ov.rapid());
            j.addProperty("spindle", ov.spindle());
            o.add("overrides", j);
        } else {
            o.add("overrides", JsonNull.INSTANCE);
        }
        o.add("pins", GrblStatusParser.pins(pinLetters(s.getEnabledPins())));
        AccessoryStates acc = s.getAccessoryStates();
        StringBuilder a = new StringBuilder();
        if (acc != null) {
            if (acc.spindleCW()) a.append('S');
            if (acc.flood()) a.append('F');
            if (acc.mist()) a.append('M');
        }
        o.add("accessories", GrblStatusParser.accessories(a.toString()));
        o.add("buffer", JsonNull.INSTANCE);
        o.add("line", JsonNull.INSTANCE);
        o.addProperty("units", "mm");
        o.addProperty("raw", "");
        return o;
    }

    static String pinLetters(EnabledPins p) {
        if (p == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        if (p.x() || p.x0() || p.x1()) b.append('X');
        if (p.y() || p.y0() || p.y1()) b.append('Y');
        if (p.z() || p.z0() || p.z1()) b.append('Z');
        if (p.probe()) b.append('P');
        if (p.door()) b.append('D');
        if (p.hold()) b.append('H');
        if (p.softReset()) b.append('R');
        if (p.cycleStart()) b.append('S');
        return b.toString();
    }

    /** x, y, z in mm, or JSON null if unknown. */
    public static JsonElement position(Position p) {
        if (p == null) {
            return JsonNull.INSTANCE;
        }
        Position mm = p.getUnits() == UnitUtils.Units.INCH ? p.getPositionIn(UnitUtils.Units.MM) : p;
        if (Double.isNaN(mm.getX()) || Double.isNaN(mm.getY()) || Double.isNaN(mm.getZ())) {
            return JsonNull.INSTANCE;
        }
        return GrblStatusParser.arrayOf(round(mm.getX()), round(mm.getY()), round(mm.getZ()));
    }

    /** Position as an array that keeps NaN axes out (for probe events). */
    public static JsonArray xyz(double x, double y, double z) {
        return GrblStatusParser.arrayOf(x, y, z);
    }

    public static String unitsName(UnitUtils.Units u) {
        if (u == UnitUtils.Units.MM) {
            return "mm";
        }
        if (u == UnitUtils.Units.INCH) {
            return "inch";
        }
        return null;
    }

    static JsonElement number(Double d) {
        return d == null || d.isNaN() || d.isInfinite() ? JsonNull.INSTANCE : new JsonPrimitive(d);
    }

    static JsonElement parseIntOrNull(String s) {
        if (s == null || s.isBlank()) {
            return JsonNull.INSTANCE;
        }
        try {
            return new JsonPrimitive(Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return JsonNull.INSTANCE;
        }
    }

    /** {@code error:22} gives 22, otherwise null. */
    public static Integer errorCode(String line) {
        return code(ERROR, line);
    }

    /** {@code ALARM:1} gives 1, otherwise null. */
    public static Integer alarmCode(String line) {
        return code(ALARM, line);
    }

    private static Integer code(Pattern p, String line) {
        if (line == null) {
            return null;
        }
        Matcher m = p.matcher(line.trim());
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    public static boolean isAck(String line) {
        String l = line.trim().toLowerCase(Locale.ROOT);
        return l.equals("ok") || l.startsWith("error") || l.startsWith("alarm:");
    }

    public static boolean isWelcome(String line) {
        String l = line.trim();
        return l.startsWith("Grbl ") || l.startsWith("GrblHAL ") || l.startsWith("Grbl v");
    }

    static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    static String lower(Enum<?> e) {
        return e == null ? null : e.name().toLowerCase(Locale.ROOT);
    }
}
