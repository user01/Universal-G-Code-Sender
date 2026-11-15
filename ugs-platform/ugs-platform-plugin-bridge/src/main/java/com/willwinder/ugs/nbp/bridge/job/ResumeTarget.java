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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What to resume from ({@code POST /job/resume/plan} body {@code target}): a line, an operation, or "where it
 * stopped" (from the journal).
 */
public sealed interface ResumeTarget {

    record Line(int line) implements ResumeTarget {
    }

    record OperationName(String name) implements ResumeTarget {
    }

    record Stopped() implements ResumeTarget {
    }

    static ResumeTarget line(int line) {
        return new Line(line);
    }

    static ResumeTarget operation(String name) {
        return new OperationName(name);
    }

    static ResumeTarget stopped() {
        return new Stopped();
    }

    /** As the API's {@code target} object, echoed in {@code ResumePlan.requested}. */
    default Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (this) {
            case Line l -> m.put("line", l.line());
            case OperationName o -> m.put("operation", o.name());
            case Stopped s -> m.put("stopped", true);
        }
        return m;
    }

    /** Parses the API's {@code target} object. */
    static ResumeTarget fromJson(Map<String, Object> m) {
        if (m.get("line") instanceof Number num) {
            return line(num.intValue());
        }
        if (m.get("operation") instanceof String s) {
            return operation(s);
        }
        if (Boolean.TRUE.equals(m.get("stopped"))) {
            return stopped();
        }
        throw new IllegalArgumentException("target must be {line: n}, {operation: name} or {stopped: true}");
    }
}
