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

import com.willwinder.ugs.nbp.bridge.runtime.JobRuntime;
import com.willwinder.ugs.nbp.bridge.runtime.UgsMachineIo;
import com.willwinder.universalgcodesender.IController;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.model.BackendAPI;

import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Development-only, simulator-only: with {@code -Dugs.bridge.dev.job=<file.nc>} <em>and</em> a loopback
 * {@code -Dugs.bridge.dev.connect}, after connecting: home, set G54 to the simulator's stock
 * ({@code G10 L2 P1 X-300 Y-380 Z-55.6}), measure the BitSetter reference, turn on BitSetter tool changes, load the
 * file and start it, so the tool-change prompt can be captured with {@link DevSnapshot}. It never answers the
 * prompt. Ignored without the loopback connect property, so it can't run against a serial port.
 */
final class DevJob {
    static final String PROPERTY = "ugs.bridge.dev.job";
    private static final Logger LOGGER = Logger.getLogger(DevJob.class.getName());

    private DevJob() {
    }

    static void maybeRun(BackendAPI backend) {
        String file = System.getProperty(PROPERTY);
        if (file == null || backend == null) {
            return;
        }
        if (DevConnect.loopbackPort(System.getProperty(DevConnect.PROPERTY)) < 0) {
            LOGGER.warning(() -> "Ignoring " + PROPERTY + ": only together with a loopback " + DevConnect.PROPERTY);
            return;
        }
        Thread t = new Thread(() -> {
            try {
                waitFor(60, () -> backend.isConnected() && state(backend) != null
                        && (state(backend) == ControllerState.ALARM || state(backend) == ControllerState.IDLE));
                if (state(backend) == ControllerState.ALARM) {
                    backend.performHomingCycle();
                    Thread.sleep(1000);
                }
                waitFor(120, () -> state(backend) == ControllerState.IDLE);
                IController c = backend.getController();
                try (UgsMachineIo io = new UgsMachineIo(c, null)) {
                    io.send("G10 L2 P1 X-300 Y-380 Z-55.6");
                }
                waitFor(30, () -> JobRuntime.current() != null);
                JobRuntime rt = JobRuntime.current();
                rt.measureReference("T02", false).get(180, TimeUnit.SECONDS);
                rt.setToolChangesEnabled(true);
                backend.setGcodeFile(new File(file));
                Thread.sleep(2000);
                waitFor(30, () -> state(backend) == ControllerState.IDLE);
                backend.send();
                LOGGER.info(() -> "dev job: streaming " + file);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "dev job failed", e);
            }
        }, "ugs-bridge-dev-job");
        t.setDaemon(true);
        t.start();
    }

    private static ControllerState state(BackendAPI backend) {
        IController c = backend.getController();
        return c == null || c.getControllerStatus() == null ? null : c.getControllerStatus().getState();
    }

    private static void waitFor(int seconds, BooleanSupplier ok) throws InterruptedException {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new IllegalStateException("dev job: timed out");
            }
            Thread.sleep(200);
        }
    }
}
