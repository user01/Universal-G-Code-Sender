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
package com.willwinder.ugs.nbp.bridge.log;

/**
 * One record as written to {@code session.jsonl} (contract: bridge/docs/log-format.md).
 *
 * @param seq     process-lifetime sequence number
 * @param kind    record kind ({@link Kind})
 * @param src     who caused it ({@link Src})
 * @param session session folder name, or null before the first connection
 * @param json    the serialized record, exactly the line written to disk (without newline)
 */
public record LogRecord(long seq, String kind, String src, String session, String json) {

    /** Record kinds (log-format.md "Kinds"). */
    public static final class Kind {
        public static final String SESSION = "session";
        public static final String TX = "tx";
        public static final String RX = "rx";
        public static final String STATUS = "status";
        public static final String MESSAGE = "message";
        public static final String EVENT = "event";
        public static final String COMMAND = "command";
        public static final String FILE = "file";
        public static final String AUTHORITY = "authority";
        public static final String CLIENT = "client";
        public static final String NOTIFY = "notify";
        public static final String ORIGIN = "origin";
        public static final String TOOL = "tool";
        public static final String JOB = "job";

        private Kind() {
        }
    }

    /** Record sources (log-format.md "Record envelope"). */
    public static final class Src {
        public static final String OPERATOR = "operator";
        public static final String CLAUDE = "claude";
        public static final String UGS = "ugs";
        public static final String CONTROLLER = "controller";
        public static final String BRIDGE = "bridge";

        private Src() {
        }
    }
}
