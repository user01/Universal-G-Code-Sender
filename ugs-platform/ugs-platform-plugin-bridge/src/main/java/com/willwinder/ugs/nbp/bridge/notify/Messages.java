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
package com.willwinder.ugs.nbp.bridge.notify;

import java.util.Locale;
import java.util.Map;

/**
 * Notification texts: short and operator-useful. Emoji are used here (notification text) only.
 */
public final class Messages {
    /** Telegram allows 4096 characters; keep messages phone-sized. */
    public static final int MAX_LENGTH = 1000;

    /** GRBL 1.1 alarm codes, short wording. */
    private static final Map<Integer, String> ALARMS = Map.ofEntries(
            Map.entry(1, "hard limit"),
            Map.entry(2, "soft limit (target outside machine travel)"),
            Map.entry(3, "reset while in motion"),
            Map.entry(4, "probe fail (probe already triggered)"),
            Map.entry(5, "probe fail (no contact)"),
            Map.entry(6, "homing fail (reset during cycle)"),
            Map.entry(7, "homing fail (door opened)"),
            Map.entry(8, "homing fail (pull-off did not clear switch)"),
            Map.entry(9, "homing fail (switch not found)"),
            Map.entry(10, "homing fail (second switch not found)"));

    private Messages() {
    }

    /** Where the stream is: {@code " — name at line 1 234"}, or "" when nothing is streaming. */
    public static String where(String file, int line) {
        if (file == null) {
            return "";
        }
        return " — " + file + (line > 0 ? " at line " + count(line) : "");
    }

    public static String streamFinished(String file, Double seconds, Integer lines) {
        StringBuilder s = new StringBuilder("✔ Job finished: ").append(file == null ? "(unknown file)" : file);
        String sep = " — ";
        if (seconds != null) {
            s.append(sep).append(duration(seconds));
            sep = ", ";
        }
        if (lines != null) {
            s.append(sep).append(count(lines)).append(" lines");
        }
        return s.toString();
    }

    public static String streamError(String file, int line, String error) {
        StringBuilder s = new StringBuilder("⛔ Stream stopped on error");
        if (file != null) {
            s.append(": ").append(file);
            if (line > 0) {
                s.append(" line ").append(count(line));
            }
        }
        if (error != null && !error.isBlank()) {
            s.append(" — ").append(error);
        }
        return s.append(". Waiting in UGS.").toString();
    }

    public static String alarm(Integer code, String file, int line) {
        String text = code == null ? null : ALARMS.get(code);
        String head = code == null ? "⚠ ALARM" : "⚠ ALARM " + code;
        return head + (text == null ? "" : ": " + text) + where(file, line);
    }

    /** Alarm reported by UGS without a code (e.g. {@code hard_limit}). */
    public static String ugsAlarm(String ugsAlarm, String file, int line) {
        String name = ugsAlarm == null || ugsAlarm.startsWith("unk") ? "unknown" : ugsAlarm.replace('_', ' ');
        return "⚠ ALARM: " + name + where(file, line);
    }

    public static String feedHold(String state, long seconds, String file, int line) {
        String head = "door".equals(state) ? "🚪 Safety door open for " : "⏸ Feed hold for ";
        return head + duration(seconds) + where(file, line);
    }

    /** @param tool e.g. "T21 Whiteside RU1600 1/8 in upcut", or null if unknown */
    public static String toolChange(String tool, String file, int line) {
        StringBuilder s = new StringBuilder("🔧 Tool change: ");
        s.append(tool == null ? "change the tool" : "install " + tool);
        if (file != null) {
            s.append(" (").append(file);
            if (line > 0) {
                s.append(", line ").append(count(line));
            }
            s.append(')');
        }
        return s.append(". Confirm in UGS.").toString();
    }

    public static String toolChangeNoReference(String file, int line) {
        return "🔧 Tool change stopped: no BitSetter reference; parked at safe Z, spindle off" + where(file, line)
                + ". Answer in UGS.";
    }

    public static String toolChangeFailed(String file, int line) {
        return "🔧 Tool change routine failed; stream left paused" + where(file, line);
    }

    public static String connectionLost(String port, String file, int line) {
        return "🔌 Connection to the controller lost" + (port == null ? "" : " (" + port + ")")
                + (file == null ? "" : " — during " + file + (line > 0 ? " at line " + count(line) : ""));
    }

    public static String progress(int percent, String file, int line, int lines, Double elapsedSeconds) {
        StringBuilder s = new StringBuilder("▶ ").append(percent).append(" % — ").append(file)
                .append(", line ").append(count(line)).append(" of ").append(count(lines));
        if (elapsedSeconds != null && elapsedSeconds > 0) {
            s.append(", ").append(duration(elapsedSeconds)).append(" elapsed");
            if (percent > 0 && percent < 100) {
                s.append(", ~").append(duration(elapsedSeconds * (100 - percent) / percent)).append(" left");
            }
        }
        return s.toString();
    }

    public static String claudeMessage(String text) {
        return "💬 Claude: " + text;
    }

    public static String claudeAuthorityRequest(String text) {
        return "🔑 Claude asks: " + text;
    }

    /** Text for an authority request, for the API: "arm Motion (now Query): probe the stock top". */
    public static String authorityRequest(String requested, String current, String reason) {
        StringBuilder s = new StringBuilder("raise access to ").append(requested);
        if (current != null) {
            s.append(" (now ").append(current).append(')');
        }
        if (reason != null && !reason.isBlank()) {
            s.append(": ").append(reason);
        }
        return s.append(". Approve in UGS.").toString();
    }

    public static String test() {
        return "🧪 ugs-bridge test notification. If you can read this, the sink works.";
    }

    /** 26781 gives "26 781". */
    public static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n).replace(',', ' ');
    }

    /** 45 s, 90 s, 61 min, 2 h 05 min. */
    public static String duration(double seconds) {
        long s = Math.round(seconds);
        if (s < 120) {
            return s + " s";
        }
        long min = Math.round(seconds / 60.0);
        if (min < 100) {
            return min + " min";
        }
        return String.format(Locale.ROOT, "%d h %02d min", min / 60, min % 60);
    }

    static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String t = text.strip();
        return t.length() <= MAX_LENGTH ? t : t.substring(0, MAX_LENGTH - 1) + "…";
    }
}
