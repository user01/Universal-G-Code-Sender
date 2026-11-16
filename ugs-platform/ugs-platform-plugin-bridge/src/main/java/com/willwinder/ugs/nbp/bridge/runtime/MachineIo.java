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

/**
 * The few machine operations the bridge's routines need (tool change, reference measurement, apply origin). The
 * UGS implementation is {@link UgsMachineIo}; tests use a fake. All positions are machine coordinates in mm.
 */
public interface MachineIo {

    /** A command was rejected (error/alarm), timed out, or the machine isn't in a state to run it. */
    class MachineException extends Exception {
        public MachineException(String message) {
            super(message);
        }

        public MachineException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Sends one line and waits until the controller answered it ({@code ok}); throws on error or alarm. */
    void send(String line) throws MachineException, InterruptedException;

    /**
     * Sends a probe line ({@code G38.2 ...}) and returns the contact position from its {@code [PRB:...:1]} report.
     * Throws when the probe didn't make contact (GRBL alarm 5) or no report arrived.
     */
    double[] probe(String line) throws MachineException, InterruptedException;

    /** Sends {@code $#} and returns the G54 offset it reports. */
    double[] readG54() throws MachineException, InterruptedException;

    /** Last reported machine position, or null if unknown. */
    double[] machinePosition();

    /** True when the last status report lists {@code pin} (e.g. {@code P}) in {@code Pn:}. */
    boolean pinActive(String pin);
}
