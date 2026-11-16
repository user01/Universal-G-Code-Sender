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
package com.willwinder.ugs.nbp.bridge.runtime;

import com.willwinder.universalgcodesender.IController;
import com.willwinder.universalgcodesender.communicator.ICommunicator;
import com.willwinder.universalgcodesender.communicator.ICommunicatorListener;
import com.willwinder.universalgcodesender.listeners.ControllerStatus;
import com.willwinder.universalgcodesender.listeners.EnabledPins;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils;
import com.willwinder.universalgcodesender.types.GcodeCommand;
import com.willwinder.universalgcodesender.utils.ControllerUtils;

import java.time.Duration;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link MachineIo} over a connected UGS controller: commands go through {@code sendCommandImmediately} (as UGS's
 * own tool-change interceptor does) and the {@code [PRB:...]} / {@code [G54:...]} answers are read from the
 * communicator. Close it to detach. GRBL only ({@code [PRB:x,y,z:1]}, {@code $#}).
 */
public final class UgsMachineIo implements MachineIo, ICommunicatorListener, AutoCloseable {
    private static final Pattern PRB = Pattern.compile("\\[PRB:([-0-9.]+),([-0-9.]+),([-0-9.]+)[^:]*:([01])]");
    private static final Pattern G54 = Pattern.compile("\\[G54:([-0-9.]+),([-0-9.]+),([-0-9.]+)[^]]*]");
    private static final Duration MOVE_TIMEOUT = Duration.ofMinutes(3);
    private static final long REPORT_WAIT_MS = 2000;

    private final IController controller;
    private final ICommunicator communicator;
    private final Consumer<String> sentLog;
    private final Object lock = new Object();
    private long probeSeq;
    private double[] probe;
    private boolean probeOk;
    private long g54Seq;
    private double[] g54;

    /**
     * @param sentLog called with each line before it is sent (may be null)
     */
    public UgsMachineIo(IController controller, Consumer<String> sentLog) {
        this.controller = controller;
        this.communicator = controller.getCommunicator();
        this.sentLog = sentLog;
        if (communicator != null) {
            communicator.addListener(this);
        }
    }

    @Override
    public void close() {
        if (communicator != null) {
            communicator.removeListener(this);
        }
    }

    @Override
    public void send(String line) throws MachineException, InterruptedException {
        GcodeCommand c = run(line);
        if (c.isError()) {
            throw new MachineException("'" + line + "' -> " + oneLine(c.getResponse()));
        }
    }

    private GcodeCommand run(String line) throws MachineException, InterruptedException {
        if (sentLog != null) {
            sentLog.accept(line);
        }
        GcodeCommand c;
        try {
            c = controller.createCommand(line);
        } catch (Exception e) {
            throw new MachineException("can't create '" + line + "': " + e.getMessage(), e);
        }
        try {
            ControllerUtils.sendAndWaitForCompletion(controller, c, MOVE_TIMEOUT);
        } catch (InterruptedException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw e;
            }
            throw new MachineException(e.getMessage(), e); // UGS reports timeouts as InterruptedException
        } catch (RuntimeException e) {
            throw new MachineException("'" + line + "' could not be sent: " + e.getMessage(), e);
        }
        return c;
    }

    @Override
    public double[] probe(String line) throws MachineException, InterruptedException {
        long before;
        synchronized (lock) {
            before = probeSeq;
        }
        GcodeCommand c = run(line);
        long deadline = System.currentTimeMillis() + REPORT_WAIT_MS;
        synchronized (lock) {
            while (probeSeq == before && System.currentTimeMillis() < deadline) {
                lock.wait(50);
            }
            if (probeSeq == before) {
                throw new MachineException("'" + line + "': no probe report" + (c.isError() ? " (" + oneLine(c.getResponse()) + ")" : ""));
            }
            if (!probeOk) {
                throw new MachineException("'" + line + "': the probe made no contact within its travel");
            }
            if (c.isError()) {
                throw new MachineException("'" + line + "' -> " + oneLine(c.getResponse()));
            }
            return toMm(probe);
        }
    }

    @Override
    public double[] readG54() throws MachineException, InterruptedException {
        long before;
        synchronized (lock) {
            before = g54Seq;
        }
        send("$#");
        long deadline = System.currentTimeMillis() + REPORT_WAIT_MS;
        synchronized (lock) {
            while (g54Seq == before && System.currentTimeMillis() < deadline) {
                lock.wait(50);
            }
            if (g54Seq == before) {
                throw new MachineException("'$#' gave no [G54:...] line");
            }
            return toMm(g54);
        }
    }

    @Override
    public double[] machinePosition() {
        ControllerStatus s = controller.getControllerStatus();
        Position p = s == null ? null : s.getMachineCoord();
        if (p == null) {
            return null;
        }
        Position mm = p.getPositionIn(UnitUtils.Units.MM);
        return new double[]{mm.getX(), mm.getY(), mm.getZ()};
    }

    @Override
    public boolean pinActive(String pin) {
        ControllerStatus s = controller.getControllerStatus();
        EnabledPins pins = s == null ? null : s.getEnabledPins();
        if (pins == null) {
            return false;
        }
        return switch (pin == null ? "P" : pin.toUpperCase(Locale.ROOT)) {
            case "X" -> pins.x();
            case "Y" -> pins.y();
            case "Z" -> pins.z();
            case "D" -> pins.door();
            default -> pins.probe();
        };
    }

    private double[] toMm(double[] v) {
        boolean inch = false;
        try {
            inch = controller.getFirmwareSettings().getReportingUnits() == UnitUtils.Units.INCH;
        } catch (RuntimeException ignored) {
            // unknown: GRBL default is mm ($13=0)
        }
        double k = inch ? 25.4 : 1.0;
        return new double[]{v[0] * k, v[1] * k, v[2] * k};
    }

    // ---------------------------------------------------------------- communicator

    @Override
    public void rawResponseListener(String response) {
        if (response == null) {
            return;
        }
        Matcher m = PRB.matcher(response);
        if (m.find()) {
            synchronized (lock) {
                probe = new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3))};
                probeOk = "1".equals(m.group(4));
                probeSeq++;
                lock.notifyAll();
            }
            return;
        }
        m = G54.matcher(response);
        if (m.find()) {
            synchronized (lock) {
                g54 = new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3))};
                g54Seq++;
                lock.notifyAll();
            }
        }
    }

    @Override
    public void commandSent(GcodeCommand command) {
        // not needed
    }

    @Override
    public void commandSkipped(GcodeCommand command) {
        // not needed
    }

    @Override
    public void communicatorPausedOnError() {
        // not needed
    }

    @Override
    public void onConnectionClosed() {
        // not needed
    }

    private static String oneLine(String s) {
        return s == null ? "(no answer)" : s.replace('\n', ' ').trim();
    }
}
