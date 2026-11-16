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

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Cross-language contract: runs the MCP server's own {@code BridgeClient} (bridge/mcp) against this Java server
 * ({@code src/test/python/contract_client.py}): status, query, settings, 403, 401, events WS, lock → 4423 and 423.
 * Skipped when {@code uv} or the bridge workspace isn't available.
 */
public class ContractPythonTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void pythonBridgeClientAgainstTheJavaServer() throws Exception {
        Path bridgeDir = findBridgeDir();
        Assume.assumeTrue("bridge/ not found", bridgeDir != null);
        String uv = findUv();
        Assume.assumeTrue("uv not found", uv != null);
        Path script = bridgeDir.resolveSibling("ugs-platform/ugs-platform-plugin-bridge/src/test/python/contract_client.py");
        try (ApiFixture f = new ApiFixture(tmp.newFolder("home").toPath())) {
            f.unlock(Level.QUERY);
            ProcessBuilder pb = new ProcessBuilder(uv, "run", "--frozen", "--package", "ugs-mcp", "python", script.toString(),
                    f.home.toString()).directory(bridgeDir.toFile()).redirectErrorStream(true);
            pb.environment().put("UGS_BRIDGE_HOME", f.home.toString());
            Process p = pb.start();
            List<String> out = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                 OutputStream in = p.getOutputStream()) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.add(line);
                    if (line.equals("LOCK_ME")) {
                        f.lock.lock(); // the operator clicks the padlock
                        in.write("locked\n".getBytes(StandardCharsets.UTF_8));
                        in.flush();
                    }
                }
            }
            assertTrue("python timed out", p.waitFor(180, TimeUnit.SECONDS));
            String text = String.join("\n", out);
            Assume.assumeFalse("uv could not prepare the env:\n" + text,
                    text.contains("error: Failed to") || text.contains("No module named 'ugs_mcp'"));
            System.out.println(text);
            assertEquals(text, 0, p.exitValue());
            assertTrue(text, text.contains("CONTRACT OK"));
        }
    }

    static Path findBridgeDir() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("bridge/mcp/src/ugs_mcp/bridge_client.py"))) {
                return dir.resolve("bridge");
            }
            dir = dir.getParent();
        }
        return null;
    }

    static String findUv() {
        List<String> candidates = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String d : path.split(File.pathSeparator)) {
                candidates.add(d + "/uv");
            }
        }
        candidates.add(System.getProperty("user.home") + "/.local/bin/uv");
        candidates.add("/opt/homebrew/bin/uv");
        return candidates.stream().filter(c -> Files.isExecutable(Paths.get(c))).findFirst().orElse(null);
    }
}
