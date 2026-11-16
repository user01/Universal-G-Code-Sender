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
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class DiscoveryTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void tokenAndDiscoveryFilesAreOwnerOnly() throws Exception {
        Path home = tmp.newFolder("home").toPath();
        Discovery.write(home, 8765, "abc", "/api/bridge/v1");
        for (String f : new String[]{"token", "bridge.json"}) {
            assertEquals(f, "rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve(f))));
        }
        assertEquals("abc", Files.readString(home.resolve("token")).strip());
        JsonObject d = JsonParser.parseString(Files.readString(home.resolve("bridge.json"))).getAsJsonObject();
        assertEquals("ugs-bridge-discovery/1", d.get("format").getAsString());
        assertEquals(8765, d.get("port").getAsInt());
        assertEquals(ProcessHandle.current().pid(), d.get("pid").getAsLong());
        assertEquals("/api/bridge/v1", d.get("api").getAsString());
        try (var s = Files.list(home)) {
            assertEquals("no temp files left", 2, s.count());
        }
    }

    @Test
    public void rewritingNarrowsAWideFileAndReplacesTheToken() throws Exception {
        Path home = tmp.newFolder("home").toPath();
        Files.writeString(home.resolve("token"), "old");
        Files.setPosixFilePermissions(home.resolve("token"), PosixFilePermissions.fromString("rw-r--r--"));
        Discovery.write(home, 8766, Discovery.newToken(), "/api/bridge/v1");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve("token"))));
        assertNotEquals("old", Files.readString(home.resolve("token")).strip());
    }

    @Test
    public void tokensAreRandomAndLong() {
        String a = Discovery.newToken();
        String b = Discovery.newToken();
        assertNotEquals(a, b);
        assertEquals(43, a.length());
        assertTrue(a.matches("[A-Za-z0-9_-]+"));
    }

    @Test
    public void removeOnlyDeletesOurOwnDiscoveryFile() throws Exception {
        Path home = tmp.newFolder("home").toPath();
        Discovery.write(home, 8765, "t", "/api/bridge/v1");
        Discovery.remove(home);
        assertFalse(Files.exists(home.resolve("bridge.json")));
        Files.writeString(home.resolve("bridge.json"), "{\"format\":\"ugs-bridge-discovery/1\",\"port\":1,\"pid\":1,\"api\":\"x\"}");
        Discovery.remove(home);
        assertTrue("another process's file stays", Files.exists(home.resolve("bridge.json")));
    }
}
