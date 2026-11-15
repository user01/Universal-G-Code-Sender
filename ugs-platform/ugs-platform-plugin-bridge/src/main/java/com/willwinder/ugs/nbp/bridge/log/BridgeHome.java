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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;

/**
 * Resolves the bridge home directory (log-format.md "Files").
 * Order: system property {@value #PROPERTY}, environment variable {@value #ENV},
 * then {@code ~/Library/Application Support/ugs-bridge}.
 */
public final class BridgeHome {
    public static final String ENV = "UGS_BRIDGE_HOME";
    public static final String PROPERTY = "ugs.bridge.home";

    private BridgeHome() {
    }

    public static Path resolve() {
        return resolve(System.getProperties(), System.getenv());
    }

    static Path resolve(Properties props, Map<String, String> env) {
        String prop = props.getProperty(PROPERTY);
        if (prop != null && !prop.isBlank()) {
            return Paths.get(prop);
        }
        String fromEnv = env.get(ENV);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Paths.get(fromEnv);
        }
        return Paths.get(props.getProperty("user.home"), "Library", "Application Support", "ugs-bridge");
    }
}
