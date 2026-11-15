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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the post-processor comment headers (SPEC §6.3). The patterns are listed in {@code package-info.java}.
 * Works on whole-line comments only ({@code (...)} lines); never interprets G-code.
 */
final class HeaderParser {
    static final Pattern OP_PIPE = Pattern.compile(
            "^\\(\\s*-{2,}\\s*OP\\s+(\\d+)\\s*\\|\\s*(T\\d+)\\s*\\|\\s*([^|]+?)\\s*(?:\\|\\s*(.*?))?\\s*-{2,}\\s*\\)\\s*$");
    static final Pattern OP_COLON = Pattern.compile(
            "^\\(\\s*-{2,}\\s*OP\\s+(\\d+)\\s*:\\s*(.*?)\\s*-{2,}\\s*\\)\\s*$");
    static final Pattern FEED_PLUNGE = Pattern.compile(
            "feed\\s+(\\d+(?:\\.\\d+)?)\\s*/\\s*plunge\\s+(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
    static final Pattern FEED_ONLY = Pattern.compile("feed\\s+(\\d+(?:\\.\\d+)?)\\s*mm/min", Pattern.CASE_INSENSITIVE);
    static final Pattern FEEDS_BLOCK = Pattern.compile(
            "FEEDS:\\s*(\\d+(?:\\.\\d+)?)\\s*mm/min\\s+cut\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*mm/min\\s+plunge",
            Pattern.CASE_INSENSITIVE);
    /** A tool at the start of a comment, after "N.", after a "LABEL:" or after "tool". */
    static final Pattern TOOL_LINE = Pattern.compile(
            "^\\(\\s*(?:\\d+\\.\\s+|[A-Za-z][A-Za-z \\[\\]]*:\\s*|tool\\s+)?T(\\d{1,3})(?![\\w\\]])");
    static final Pattern TOOL_NAME = Pattern.compile(
            "^\\s+(.+?)(?=\\s{3,}|,?\\s+feed\\b|\\)\\s*$)", Pattern.CASE_INSENSITIVE);
    static final Pattern DIAMETER_MM = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*mm\\b");
    static final Pattern CLEARANCE = Pattern.compile(
            "^\\(\\s*CLEARANCE\\s*:.*?\\bZ\\s*([+-]?\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
    static final Pattern FREECAD_BEGIN = Pattern.compile("^\\(\\s*Begin operation:\\s*(.+?)\\s*\\)\\s*$");
    static final Pattern FREECAD_FINISH = Pattern.compile("^\\(\\s*Finish operation:\\s*(.+?)\\s*\\)\\s*$");

    /** An {@code (---- OP ...)} header. {@code toolId} is null for the colon form. */
    record OpHeader(int line, int number, String toolId, String name, Double cutFeed, Double plungeFeed) {
    }

    /** A {@code (FEEDS: x mm/min cut, y mm/min plunge)} line inside a tool-change block. */
    record FeedsLine(int line, double cut, double plunge) {
    }

    record FreecadOp(int beginLine, int finishLine, String name) {
    }

    Double clearance;
    final Map<Integer, ToolInfo> tools = new LinkedHashMap<>();
    final List<OpHeader> opHeaders = new ArrayList<>();
    final List<FeedsLine> feedsLines = new ArrayList<>();
    final List<FreecadOp> freecadOps = new ArrayList<>();

    static boolean isCommentLine(String raw) {
        String t = raw.trim();
        return t.startsWith("(") && t.endsWith(")");
    }

    static HeaderParser parse(List<String> lines) {
        HeaderParser h = new HeaderParser();
        int lastToolLine = -10;
        int lastToolNumber = -1;
        String openFreecad = null;
        int openFreecadLine = 0;
        for (int i = 1; i <= lines.size(); i++) {
            String raw = lines.get(i - 1);
            if (!isCommentLine(raw)) {
                continue;
            }
            String t = raw.trim();
            Matcher m;
            if (h.clearance == null && (m = CLEARANCE.matcher(t)).find()) {
                h.clearance = Double.parseDouble(m.group(1));
                continue;
            }
            if ((m = OP_PIPE.matcher(t)).matches()) {
                Double[] fp = detailFeeds(lines, i);
                h.opHeaders.add(new OpHeader(i, Integer.parseInt(m.group(1)), m.group(2), m.group(3).trim(), fp[0], fp[1]));
                continue;
            }
            if ((m = OP_COLON.matcher(t)).matches()) {
                Double[] fp = detailFeeds(lines, i);
                h.opHeaders.add(new OpHeader(i, Integer.parseInt(m.group(1)), null, m.group(2).trim(), fp[0], fp[1]));
                continue;
            }
            if ((m = FEEDS_BLOCK.matcher(t)).find()) {
                h.feedsLines.add(new FeedsLine(i, Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))));
                continue;
            }
            if ((m = FREECAD_BEGIN.matcher(t)).matches()) {
                openFreecad = m.group(1);
                openFreecadLine = i;
                continue;
            }
            if ((m = FREECAD_FINISH.matcher(t)).matches()) {
                if (openFreecad != null) {
                    h.freecadOps.add(new FreecadOp(openFreecadLine, i, openFreecad));
                    openFreecad = null;
                }
                continue;
            }
            if (t.toUpperCase().contains("OPERATIONS")) {
                continue;
            }
            Matcher tok = TOOL_LINE.matcher(t);
            if (tok.find()) {
                int number = Integer.parseInt(tok.group(1));
                String id = "T" + tok.group(1);
                String name = null;
                Double diameter = null;
                Matcher nm = TOOL_NAME.matcher(t.substring(tok.end()));
                if (nm.find()) {
                    name = nm.group(1).trim().replaceAll(",$", "").trim();
                    Matcher dm = DIAMETER_MM.matcher(name);
                    if (dm.find()) {
                        diameter = Double.parseDouble(dm.group(1));
                        name = null;
                    } else if (name.isEmpty() || name.startsWith("-") || name.startsWith("M6")) {
                        name = null;
                    }
                }
                Double cut = null;
                Double plunge = null;
                Matcher fm = FEED_PLUNGE.matcher(t);
                if (fm.find()) {
                    cut = Double.parseDouble(fm.group(1));
                    plunge = Double.parseDouble(fm.group(2));
                } else if ((fm = FEED_ONLY.matcher(t)).find()) {
                    cut = Double.parseDouble(fm.group(1));
                }
                h.mergeTool(new ToolInfo(id, number, name, diameter, cut, plunge));
                lastToolLine = i;
                lastToolNumber = number;
                continue;
            }
            // A continuation line ("(   green label ... feed 1778 / plunge 508 mm/min)") right below a tool line.
            Matcher fm = FEED_PLUNGE.matcher(t);
            if (fm.find() && i - lastToolLine <= 2 && lastToolNumber >= 0) {
                ToolInfo prev = h.tools.get(lastToolNumber);
                h.mergeTool(new ToolInfo(prev.id(), lastToolNumber, null, null,
                        Double.parseDouble(fm.group(1)), Double.parseDouble(fm.group(2))));
            }
        }
        return h;
    }

    private void mergeTool(ToolInfo t) {
        tools.merge(t.number(), t, ToolInfo::merge);
    }

    /** Feed / plunge from the comment lines right below an op header (until the first non-comment line). */
    private static Double[] detailFeeds(List<String> lines, int headerLine) {
        for (int j = headerLine + 1; j <= Math.min(lines.size(), headerLine + 6); j++) {
            String raw = lines.get(j - 1);
            if (!isCommentLine(raw)) {
                break;
            }
            Matcher m = FEED_PLUNGE.matcher(raw);
            if (m.find()) {
                return new Double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))};
            }
        }
        return new Double[]{null, null};
    }
}
