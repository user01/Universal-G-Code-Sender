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

import org.openide.windows.WindowManager;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Development-only: paints the UGS main window into a PNG when a request file appears, so the window can be
 * captured without OS screen-recording permission. Enabled with {@code -Dugs.bridge.dev.snapshots=<dir>} (dev.sh run).
 * Touch {@code <dir>/<name>.request}; the image is written to {@code <dir>/<name>.png} and the request is deleted.
 */
final class DevSnapshot {
    static final String PROPERTY = "ugs.bridge.dev.snapshots";
    private static final Logger LOGGER = Logger.getLogger(DevSnapshot.class.getName());

    private DevSnapshot() {
    }

    static void maybeStart() {
        String dir = System.getProperty(PROPERTY);
        if (dir == null || dir.isBlank()) {
            return;
        }
        Path root = Path.of(dir);
        Thread t = new Thread(() -> loop(root), "ugs-bridge-dev-snapshot");
        t.setDaemon(true);
        t.start();
    }

    private static void loop(Path root) {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "dev snapshots disabled", e);
            return;
        }
        while (true) {
            try (DirectoryStream<Path> requests = Files.newDirectoryStream(root, "*.request")) {
                for (Path req : requests) {
                    String name = req.getFileName().toString().replaceFirst("\\.request$", "");
                    capture(root.resolve(name + ".png"));
                    Files.deleteIfExists(req);
                }
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "dev snapshot failed", e);
            }
        }
    }

    static void capture(Path out) throws Exception {
        AtomicReference<BufferedImage> image = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Frame frame = WindowManager.getDefault().getMainWindow();
            int w = Math.max(1, frame.getWidth());
            int h = Math.max(1, frame.getHeight());
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            try {
                frame.paint(g);
                // dialogs (e.g. the tool-change prompt) are separate windows: paint them on top, where they are
                for (java.awt.Window win : java.awt.Window.getWindows()) {
                    if (win != frame && win.isShowing() && win.getWidth() > 0) {
                        java.awt.Point p = win.getLocationOnScreen();
                        java.awt.Point f = frame.getLocationOnScreen();
                        Graphics2D wg = (Graphics2D) g.create(p.x - f.x, p.y - f.y, win.getWidth(), win.getHeight());
                        try {
                            win.paint(wg);
                        } finally {
                            wg.dispose();
                        }
                    }
                }
            } finally {
                g.dispose();
            }
            image.set(img);
        });
        ImageIO.write(image.get(), "png", out.toFile());
        LOGGER.info(() -> "dev snapshot written: " + out);
    }
}
