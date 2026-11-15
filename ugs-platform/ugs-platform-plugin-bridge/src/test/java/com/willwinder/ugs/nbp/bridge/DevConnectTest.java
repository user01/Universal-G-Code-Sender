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

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DevConnectTest {
    @Test
    public void acceptsOnlyLoopback() {
        assertEquals(2323, DevConnect.loopbackPort("127.0.0.1:2323"));
        assertEquals(2323, DevConnect.loopbackPort("localhost:2323"));
        assertEquals(-1, DevConnect.loopbackPort("192.168.1.5:2323"));
        assertEquals(-1, DevConnect.loopbackPort("/dev/cu.usbmodem1101"));
        assertEquals(-1, DevConnect.loopbackPort("127.0.0.1:0"));
        assertEquals(-1, DevConnect.loopbackPort("127.0.0.1"));
        assertEquals(-1, DevConnect.loopbackPort(null));
    }
}
