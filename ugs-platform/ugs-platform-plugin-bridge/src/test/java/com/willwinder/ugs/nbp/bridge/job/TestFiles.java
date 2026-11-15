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
package com.willwinder.ugs.nbp.bridge.job;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assume.assumeTrue;

/** Locates repo files from the module's test working directory. */
final class TestFiles {
    private TestFiles() {
    }

    static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("bridge/schema"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("repo root not found");
        }
        return p;
    }

    /** A real job file from bridge/testdata/nc (gitignored); skips the test if absent. */
    static Path realNc(String name) {
        Path p = repoRoot().resolve("bridge/testdata/nc").resolve(name);
        assumeTrue("missing test data " + p, Files.isRegularFile(p));
        return p;
    }

    static MachineProfile profile() throws IOException {
        return MachineProfile.read(repoRoot().resolve("bridge/profiles/shapeoko4-standard.json"));
    }
}
