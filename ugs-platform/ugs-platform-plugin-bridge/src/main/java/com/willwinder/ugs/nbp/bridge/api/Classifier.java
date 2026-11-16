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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Authority classification of command lines (bridge/docs/api.md "Authority classification"). Mirrors
 * {@code ugs_mcp.safety.classify_line}; the Java side is the one that is enforced.
 */
public final class Classifier {
    /** Line classes, as in api.md. */
    public enum LineClass {
        REALTIME, QUERY, CONFIRM, MOTION, STREAM;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * @param line     normalized line (comments and whitespace removed, upper case)
     * @param cls      class
     * @param required minimum level
     */
    public record Classification(String line, LineClass cls, Level required) {
        public boolean needsOperator() {
            return cls == LineClass.CONFIRM;
        }
    }

    static final Set<String> QUERY_LINES = Set.of("?", "$$", "$#", "$G", "$I", "$N");
    static final Set<String> CONFIRM_LINES = Set.of("$H", "$X");
    private static final Pattern SETTING_WRITE = Pattern.compile("^\\$(\\d+=|N\\d+=|RST=)");

    private Classifier() {
    }

    /** Comments ({@code (...)} and {@code ;...}) and whitespace removed, upper case. */
    public static String normalize(String line) {
        StringBuilder sb = new StringBuilder();
        boolean paren = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (paren) {
                if (c == ')') {
                    paren = false;
                }
                continue;
            }
            if (c == '(') {
                paren = true;
            } else if (c == ';') {
                break;
            } else if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString().toUpperCase(Locale.ROOT);
    }

    /**
     * Why a line can't be sent as a line at all, or null if it can. GRBL picks real-time characters out of the
     * stream anywhere (even inside comments), so {@code !}, {@code ~} and {@code ?} inside a line, control
     * characters and non-ASCII bytes are refused; those go through {@code POST /realtime}.
     */
    public static String invalidReason(String rawLine) {
        if (rawLine.length() > 256) {
            return "line is longer than 256 characters";
        }
        String trimmed = rawLine.strip();
        for (int i = 0; i < rawLine.length(); i++) {
            char c = rawLine.charAt(i);
            if (c == '\n' || c == '\r') {
                return "one line per entry; no line breaks";
            }
            if ((c < 0x20 && c != '\t') || c >= 0x7f) {
                return String.format("control or non-ASCII character 0x%02x", (int) c);
            }
            if ((c == '!' || c == '~' || c == '?') && !"?".equals(trimmed)) {
                return "real-time character '" + c + "' inside a line; use POST /realtime";
            }
        }
        return null;
    }

    public static Classification classify(String rawLine) {
        String norm = normalize(rawLine);
        if (norm.isEmpty() || QUERY_LINES.contains(norm)) {
            return new Classification(norm, LineClass.QUERY, Level.QUERY);
        }
        if (CONFIRM_LINES.contains(norm)) {
            return new Classification(norm, LineClass.CONFIRM, Level.MOTION);
        }
        if (SETTING_WRITE.matcher(norm).find()) {
            return new Classification(norm, LineClass.MOTION, Level.MOTION);
        }
        return new Classification(norm, LineClass.MOTION, Level.MOTION);
    }
}
