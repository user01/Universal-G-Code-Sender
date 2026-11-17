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
package com.willwinder.ugs.nbp.bridge.eta;

import com.google.gson.JsonObject;
import com.willwinder.ugs.nbp.bridge.log.LogRecord;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;

import java.util.Objects;

/**
 * Writes {@code event} records of type {@code eta} (the {@link EtaTracker.Snapshot} plus a {@code reason}): when the
 * estimate is first known for a stream ({@code start}), every {@link #EVERY_S} s while it runs ({@code tick}), when the
 * operation, the tool or the tool-change wait changes ({@code op}, {@code tool}, {@code tool_change}) and when it ends
 * ({@code done}). {@link #tick()} is called every 2 s.
 */
public final class EtaEvents {
    static final double EVERY_S = 30;

    private final EtaTracker eta;
    private final SessionLog log;
    private boolean started;
    private boolean wasActive;
    private double lastAt = -1;
    private String lastOp;
    private String lastTool;
    private boolean lastWaiting;

    public EtaEvents(EtaTracker eta, SessionLog log) {
        this.eta = eta;
        this.log = log;
    }

    public synchronized void tick() {
        EtaTracker.Snapshot s = eta.snapshot();
        if (s == null) {
            return;
        }
        if (s.active() && !wasActive) {
            started = false;
            lastAt = -1;
            lastOp = null;
            lastTool = null;
            lastWaiting = false;
        }
        String reason = null;
        if (s.active()) {
            if (s.estimate() != null) {
                String op = s.currentOp() == null ? null : s.currentOp().name();
                if (!started) {
                    reason = "start";
                    started = true;
                } else if (s.waitingForToolChange() != lastWaiting) {
                    reason = "tool_change";
                } else if (!Objects.equals(s.currentTool(), lastTool)) {
                    reason = "tool";
                } else if (!Objects.equals(op, lastOp)) {
                    reason = "op";
                } else if (s.elapsedS() - lastAt >= EVERY_S) {
                    reason = "tick";
                }
                lastOp = op;
                lastTool = s.currentTool();
                lastWaiting = s.waitingForToolChange();
            }
        } else if (wasActive) {
            reason = "done";
        }
        wasActive = s.active();
        if (reason != null) {
            lastAt = s.elapsedS();
            JsonObject d = s.toJson();
            d.addProperty("type", "eta");
            d.addProperty("reason", reason);
            log.append(LogRecord.Kind.EVENT, LogRecord.Src.BRIDGE, d);
        }
    }
}
