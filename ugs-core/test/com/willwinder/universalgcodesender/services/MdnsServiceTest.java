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
package com.willwinder.universalgcodesender.services;

import org.junit.Test;

import javax.jmdns.NetworkTopologyDiscovery;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ugs-bridge patch P5: mDNS discovery is opt-in.
 */
public class MdnsServiceTest {
    @Test
    public void disabledByDefaultAndTouchesNoInterface() {
        String before = System.getProperty(MdnsService.ENABLED_PROPERTY);
        System.clearProperty(MdnsService.ENABLED_PROPERTY);
        AtomicBoolean asked = new AtomicBoolean();
        NetworkTopologyDiscovery.Factory.setClassDelegate(() -> new NetworkTopologyDiscovery() {
            @Override public InetAddress[] getInetAddresses() { asked.set(true); return new InetAddress[0]; }
            @Override public boolean useInetAddress(NetworkInterface ni, InetAddress a) { return false; }
            @Override public void lockInetAddress(InetAddress a) { }
            @Override public void unlockInetAddress(InetAddress a) { }
        });
        try {
            assertFalse(MdnsService.isEnabled());
            MdnsService service = new MdnsService();
            service.registerListener("_telnet._tcp.local.");
            assertTrue(service.getServices("_telnet._tcp.local.").isEmpty());
            assertFalse("no network interface may be enumerated", asked.get());
        } finally {
            NetworkTopologyDiscovery.Factory.setClassDelegate(null);
            if (before != null) {
                System.setProperty(MdnsService.ENABLED_PROPERTY, before);
            }
        }
    }

    @Test
    public void enabledOnlyByProperty() {
        String before = System.getProperty(MdnsService.ENABLED_PROPERTY);
        try {
            System.setProperty(MdnsService.ENABLED_PROPERTY, "true");
            assertTrue(MdnsService.isEnabled());
            System.setProperty(MdnsService.ENABLED_PROPERTY, "false");
            assertFalse(MdnsService.isEnabled());
        } finally {
            if (before == null) {
                System.clearProperty(MdnsService.ENABLED_PROPERTY);
            } else {
                System.setProperty(MdnsService.ENABLED_PROPERTY, before);
            }
        }
    }
}
