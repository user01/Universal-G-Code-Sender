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

import com.google.gson.JsonObject;
import com.willwinder.ugs.nbp.bridge.BridgeLock;
import com.willwinder.ugs.nbp.bridge.log.LogRecord;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Claude's authority (SPEC §4.3): the operator's MCP lock plus the level, with the Motion/Stream expiry rules.
 * <ul>
 *     <li>Only the UGS UI calls {@link #setLevel}; nothing reachable from the API can raise the level or unlock.</li>
 *     <li>Unlocking returns to the operator's base level (Query by default, or Observe); never to an armed level.</li>
 *     <li>Locking disarms immediately. Armed levels (Motion, Stream) expire after {@link #IDLE_EXPIRY} without
 *     Claude using them, and on alarm, disconnect or reset ({@link #disarm}).</li>
 *     <li>Every change is logged as an {@code authority} record.</li>
 * </ul>
 * Listeners get the new snapshot after every change (lock, level, request from Claude).
 */
public final class Authority {
    public static final Duration IDLE_EXPIRY = Duration.ofMinutes(15);

    /** Immutable view for the status endpoint and the panel. */
    public record Snapshot(boolean locked, Level level, Instant motionExpiresAt, Request pendingRequest) {
    }

    /** A request from Claude for more authority. Shown in the panel; grants nothing. */
    public record Request(Level level, String reason, Instant at) {
    }

    /** Why the level dropped (used by listeners such as the command queue to drop unsent lines). */
    public enum Change { LOCKED, UNLOCKED, LEVEL, DISARMED, REQUEST }

    public interface Listener {
        void changed(Change change, Snapshot snapshot);
    }

    private final BridgeLock lock;
    private final SessionLog log;
    private final Clock clock;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Consumer<BridgeLock.State> lockListener = this::onLock;

    // guarded by this
    private Level base = Level.QUERY;
    private Level level = Level.QUERY;
    private Instant lastUse;
    private Request pending;

    public Authority(BridgeLock lock, SessionLog log, Clock clock) {
        this.lock = lock;
        this.log = log;
        this.clock = clock;
        lock.addListener(lockListener);
    }

    public void dispose() {
        lock.removeListener(lockListener);
    }

    public BridgeLock lock() {
        return lock;
    }

    public boolean isLocked() {
        return lock.isLocked();
    }

    /** The effective level for an MCP request (meaningless while locked: every request is refused). */
    public synchronized Level level() {
        expireIfIdle();
        return level;
    }

    public synchronized Snapshot snapshot() {
        expireIfIdle();
        return new Snapshot(lock.isLocked(), level, expiresAt(), pending);
    }

    private Instant expiresAt() {
        return level.isArmed() && lastUse != null ? lastUse.plus(IDLE_EXPIRY) : null;
    }

    /**
     * The operator picks a level in the panel. Ignored while locked (the panel disables the controls).
     * Choosing Observe or Query also sets the base level that unlocking returns to.
     */
    public void setLevel(Level newLevel, String by) {
        setLevel(newLevel, by, newLevel.isArmed() ? "operator armed " + newLevel.label() : "operator set " + newLevel.label());
    }

    /** As {@link #setLevel(Level, String)} with an explicit reason (development overrides). */
    public void setLevel(Level newLevel, String by, String reason) {
        Snapshot s;
        synchronized (this) {
            if (lock.isLocked() || newLevel == level) {
                return;
            }
            level = newLevel;
            if (!newLevel.isArmed()) {
                base = newLevel;
            }
            lastUse = newLevel.isArmed() ? clock.instant() : null;
            if (pending != null && newLevel.allows(pending.level())) {
                pending = null;
            }
            logChange(reason, by);
            s = snapshotLocked();
        }
        fire(Change.LEVEL, s);
    }

    /** Claude used its armed level (a Motion or Stream request was accepted): restarts the idle timer. */
    public synchronized void touch() {
        if (level.isArmed()) {
            lastUse = clock.instant();
        }
    }

    /** Drops an armed level back to the base level (alarm, disconnect, reset, expiry). No-op when not armed. */
    public void disarm(String reason) {
        Snapshot s;
        synchronized (this) {
            if (!level.isArmed()) {
                return;
            }
            level = base;
            lastUse = null;
            logChange(reason, LogRecord.Src.BRIDGE);
            s = snapshotLocked();
        }
        fire(Change.DISARMED, s);
    }

    /** Checks the idle expiry; called by a timer and on every read of the level. */
    public synchronized void checkExpiry() {
        expireIfIdle();
    }

    /** Called with the monitor held; listeners are told on another thread so no lock is held while they run. */
    private void expireIfIdle() {
        if (level.isArmed() && lastUse != null && !clock.instant().isBefore(lastUse.plus(IDLE_EXPIRY))) {
            Level old = level;
            level = base;
            lastUse = null;
            logChange(old.label() + " expired after " + IDLE_EXPIRY.toMinutes() + " min without use", LogRecord.Src.BRIDGE);
            Snapshot s = snapshotLocked();
            Thread.ofVirtual().start(() -> fire(Change.DISARMED, s));
        }
    }

    /** {@code POST /authority/request}: remembered for the panel and logged. Grants nothing. */
    public void request(Level wanted, String reason) {
        Snapshot s;
        synchronized (this) {
            pending = new Request(wanted, reason, clock.instant());
            JsonObject d = data("Claude asks for " + wanted.label() + ": " + reason, LogRecord.Src.CLAUDE);
            d.addProperty("requested", wanted.id());
            log.append(LogRecord.Kind.AUTHORITY, LogRecord.Src.CLAUDE, d);
            s = snapshotLocked();
        }
        fire(Change.REQUEST, s);
    }

    /** The operator dismissed Claude's request in the panel. */
    public void dismissRequest() {
        Snapshot s;
        synchronized (this) {
            if (pending == null) {
                return;
            }
            pending = null;
            s = snapshotLocked();
        }
        fire(Change.REQUEST, s);
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /**
     * Development only ({@code -Dugs.bridge.dev.unlock}): unlocks with the change logged as the bridge's, with
     * the reason, instead of as the operator's.
     */
    public void unlockForDevelopment(String reason) {
        devReason = reason;
        lock.unlock();
        devReason = null;
    }

    private volatile String devReason;

    private void onLock(BridgeLock.State state) {
        Snapshot s;
        String dev = devReason;
        synchronized (this) {
            level = base;
            lastUse = null;
            if (state == BridgeLock.State.LOCKED) {
                pending = null;
            }
            if (dev != null) {
                logChange(dev, LogRecord.Src.BRIDGE);
            } else {
                logChange(state == BridgeLock.State.LOCKED ? "operator locked Claude access" : "operator unlocked Claude access",
                        LogRecord.Src.OPERATOR);
            }
            s = snapshotLocked();
        }
        fire(state == BridgeLock.State.LOCKED ? Change.LOCKED : Change.UNLOCKED, s);
    }

    private Snapshot snapshotLocked() {
        return new Snapshot(lock.isLocked(), level, expiresAt(), pending);
    }

    private void logChange(String reason, String by) {
        log.append(LogRecord.Kind.AUTHORITY, by, data(reason, by));
    }

    /** Data of an {@code authority} record with the current state. Caller holds the monitor. */
    private JsonObject data(String reason, String by) {
        JsonObject d = new JsonObject();
        d.addProperty("locked", lock.isLocked());
        d.addProperty("level", level.id());
        d.addProperty("reason", reason);
        d.addProperty("by", by);
        Instant exp = expiresAt();
        if (exp != null) {
            d.addProperty("motion_expires_at", exp.toString());
        }
        return d;
    }

    /** Logs the startup record (always locked at launch). */
    public synchronized void logStart() {
        logChange("bridge started (always locked at launch)", LogRecord.Src.BRIDGE);
    }

    private void fire(Change change, Snapshot s) {
        for (Listener l : listeners) {
            try {
                l.changed(change, s);
            } catch (RuntimeException e) {
                java.util.logging.Logger.getLogger(Authority.class.getName()).log(java.util.logging.Level.WARNING,
                        "authority listener failed", e);
            }
        }
    }
}
