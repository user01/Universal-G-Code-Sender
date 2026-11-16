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
package com.willwinder.ugs.nbm.visualizer.jogl;

import com.jogamp.opengl.GLProfile;
import org.openide.modules.ModuleInstall;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ugs-bridge patch P9: initialize JOGL's {@link GLProfile} singleton during module startup, off the AWT event
 * thread and before any window opens.
 * <p>
 * Otherwise the first {@code new GLCapabilities(...)} happens on the EDT when the visualizer opens, and on macOS
 * {@code GLProfile.initSingleton()} waits for the AppKit main thread ({@code OSXUtil.RunOnMainThread}). If AppKit
 * is at that moment answering an accessibility query, which itself waits for the EDT
 * ({@code CAccessibility.invokeAndWait}), the application deadlocks at startup. Seen 2026-11-16 with JDK 25.
 * {@link ModuleInstall#restored()} runs on the module-system startup thread before the window system is shown.
 */
public class GlStartup extends ModuleInstall {
    private static final Logger LOGGER = Logger.getLogger(GlStartup.class.getName());

    @Override
    public void restored() {
        long t0 = System.nanoTime();
        try {
            GLProfile.initSingleton();
            LOGGER.log(Level.INFO, "JOGL GLProfile initialized off the EDT in {0} ms", (System.nanoTime() - t0) / 1_000_000);
        } catch (RuntimeException | LinkageError e) {
            // The visualizer reports its own errors when it opens; never block startup on this.
            LOGGER.log(Level.WARNING, "Could not pre-initialize JOGL", e);
        }
    }
}
