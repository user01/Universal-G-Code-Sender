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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The operator's master switch for MCP access (SPEC §4.3 "MCP lock").
 * Starts LOCKED on every launch and is never persisted as unlocked.
 * Only the UGS UI may change it; nothing reachable from the API may call {@link #unlock()}.
 */
public final class BridgeLock {
    public enum State { LOCKED, UNLOCKED }

    private volatile State state = State.LOCKED;
    private final List<Consumer<State>> listeners = new CopyOnWriteArrayList<>();

    public State getState() {
        return state;
    }

    public boolean isLocked() {
        return state == State.LOCKED;
    }

    public void lock() {
        set(State.LOCKED);
    }

    public void unlock() {
        set(State.UNLOCKED);
    }

    public void addListener(Consumer<State> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<State> listener) {
        listeners.remove(listener);
    }

    private void set(State newState) {
        if (state == newState) {
            return;
        }
        state = newState;
        listeners.forEach(l -> l.accept(newState));
    }
}
