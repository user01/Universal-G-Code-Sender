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
package com.willwinder.ugs.nbp.bridge.log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SHA-256 and line count of a G-code file. Results are cached by (path, size, mtime), so a file that is
 * loaded and then streamed is hashed once.
 *
 * @param path   absolute path
 * @param sha256 lowercase hex
 * @param lines  number of lines (a final line without newline counts)
 * @param size   size in bytes
 */
public record FileFingerprint(Path path, String sha256, int lines, long size) {
    private static final int CACHE_SIZE = 32;
    private static final Map<String, FileFingerprint> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, FileFingerprint> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    public static FileFingerprint of(Path file) throws IOException {
        Path abs = file.toAbsolutePath().normalize();
        BasicFileAttributes attrs = Files.readAttributes(abs, BasicFileAttributes.class);
        String key = abs + "|" + attrs.size() + "|" + attrs.lastModifiedTime().toMillis();
        synchronized (CACHE) {
            FileFingerprint cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        FileFingerprint fp = compute(abs);
        synchronized (CACHE) {
            CACHE.put(key, fp);
        }
        return fp;
    }

    private static FileFingerprint compute(Path abs) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        int lines = 0;
        long size = 0;
        int last = '\n';
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(abs)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
                size += n;
                for (int i = 0; i < n; i++) {
                    if (buf[i] == '\n') {
                        lines++;
                    }
                }
                last = buf[n - 1];
            }
        }
        if (last != '\n') {
            lines++;
        }
        return new FileFingerprint(abs, HexFormat.of().formatHex(digest.digest()), lines, size);
    }
}
