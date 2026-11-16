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

import com.willwinder.universalgcodesender.listeners.MessageType;
import com.willwinder.universalgcodesender.model.BackendAPI;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * SPEC §4.5: warns while UGS's stock web pendant is running. It has no authentication and listens on every
 * network interface, which defeats the bridge's lock. UGS has no "is the pendant running" API, so this checks
 * every {@value #PERIOD_S} s whether something accepts connections on the pendant port from UGS's settings
 * (default 8080), and reports the auto-start setting with it. The first time it is seen, a warning goes to the
 * UGS console (and so into the session log); the bridge panel shows it while it lasts.
 */
public final class PendantWatch {
    static final int PERIOD_S = 10;

    /** What the panel shows. */
    public record State(boolean running, int port, boolean autoStart) {
        public String warning() {
            return running ? "The stock web pendant is running on port " + port
                    + ": no authentication, open on every network interface. Stop it while Claude has access"
                    + (autoStart ? " (and turn off its auto-start in the UGS settings)." : ".") : null;
        }
    }

    private final BackendAPI backend;
    private final int apiPort;
    private final List<Consumer<State>> listeners = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService timer;
    private volatile State state = new State(false, 0, false);
    private boolean warned;

    public PendantWatch(BackendAPI backend, int apiPort) {
        this.backend = backend;
        this.apiPort = apiPort;
    }

    public State state() {
        return state;
    }

    public void addListener(Consumer<State> l) {
        listeners.add(l);
    }

    public void removeListener(Consumer<State> l) {
        listeners.remove(l);
    }

    public synchronized void start() {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ugs-bridge-pendant-watch");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(this::check, 3, PERIOD_S, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }

    void check() {
        int port;
        boolean auto;
        try {
            port = backend.getSettings().getPendantPort();
            auto = backend.getSettings().isAutoStartPendant();
        } catch (RuntimeException e) {
            return;
        }
        boolean running = port > 0 && port != apiPort && listening(port);
        State s = new State(running, port, auto);
        State old = state;
        state = s;
        if (running && !warned) {
            warned = true;
            backend.dispatchMessage(MessageType.ERROR, "[bridge] " + s.warning() + "\n");
        }
        if (!s.equals(old)) {
            listeners.forEach(l -> l.accept(s));
        }
    }

    static boolean listening(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
