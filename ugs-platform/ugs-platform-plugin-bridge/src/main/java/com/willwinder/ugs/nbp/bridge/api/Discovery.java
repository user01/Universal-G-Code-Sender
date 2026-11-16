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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Set;

/**
 * Tokens and the discovery files in the bridge home (log-format.md "Files"): {@code token} (MCP bearer token) and
 * {@code bridge.json}, both mode 0600 and replaced atomically. Both tokens are new on every launch; the viewer
 * token is never written to disk (UGS passes it to the browser in the URL fragment).
 */
public final class Discovery {
    public static final String FORMAT = "ugs-bridge-discovery/1";
    public static final String TOKEN_FILE = "token";
    public static final String DISCOVERY_FILE = "bridge.json";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<PosixFilePermission> OWNER_RW = PosixFilePermissions.fromString("rw-------");

    private Discovery() {
    }

    /** 256 random bits, base64url without padding. */
    public static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static void write(Path home, int port, String token, String api) throws IOException {
        Files.createDirectories(home);
        writePrivate(home.resolve(TOKEN_FILE), token + "\n");
        JsonObject d = new JsonObject();
        d.addProperty("format", FORMAT);
        d.addProperty("port", port);
        d.addProperty("pid", ProcessHandle.current().pid());
        d.addProperty("started", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        d.addProperty("api", api);
        writePrivate(home.resolve(DISCOVERY_FILE), d + "\n");
    }

    /** Removes {@code bridge.json} if it is still ours (another UGS may have started since). The token stays. */
    public static void remove(Path home) {
        Path f = home.resolve(DISCOVERY_FILE);
        try {
            String s = Files.readString(f);
            if (s.contains("\"pid\":" + ProcessHandle.current().pid() + ",") || s.contains("\"pid\":" + ProcessHandle.current().pid() + "}")) {
                Files.deleteIfExists(f);
            }
        } catch (IOException e) {
            // nothing to remove
        }
    }

    /** Writes {@code content} to {@code target} with mode 0600 (created that way, never wider), atomically. */
    static void writePrivate(Path target, String content) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Path tmp = dir.resolve("." + target.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        Files.deleteIfExists(tmp);
        try {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(OWNER_RW));
        } catch (UnsupportedOperationException e) {
            Files.createFile(tmp);
        } catch (FileAlreadyExistsException e) {
            throw new IOException("temporary file appeared: " + tmp, e);
        }
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(tmp, OWNER_RW);
            } catch (UnsupportedOperationException e) {
                // not a POSIX file system
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
