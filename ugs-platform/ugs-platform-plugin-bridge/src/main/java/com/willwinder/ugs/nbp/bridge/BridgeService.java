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
package com.willwinder.ugs.nbp.bridge;

import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.services.LookupService;
import org.openide.modules.OnStart;

import java.util.logging.Logger;

/**
 * Entry point of the bridge module. Owns the long-lived pieces (lock, capture, session log, API).
 * W0 skeleton: only wires the lock; capture/log/API arrive in later work packages.
 */
@OnStart
public final class BridgeService implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(BridgeService.class.getName());
    private static final BridgeLock LOCK = new BridgeLock();

    public static BridgeLock lock() {
        return LOCK;
    }

    @Override
    public void run() {
        BackendAPI backend = LookupService.lookup(BackendAPI.class);
        LOGGER.info(() -> "ugs-bridge started (MCP " + LOCK.getState() + ", backend " + (backend != null ? "found" : "missing") + ")");
    }
}
