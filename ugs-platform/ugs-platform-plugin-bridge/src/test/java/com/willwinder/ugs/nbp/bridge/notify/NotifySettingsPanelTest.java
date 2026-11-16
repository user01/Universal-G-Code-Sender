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
package com.willwinder.ugs.nbp.bridge.notify;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Headless: the panel loads, edits and saves notify.json; optionally renders itself to a PNG. */
public class NotifySettingsPanelTest {
    /** Set {@code -Dnotify.panel.png=<file>} to write a render of the panel. */
    static final String PNG_PROPERTY = "notify.panel.png";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @BeforeClass
    public static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    public void loadsTheExampleMaskedAndSavesWith0600() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("notify.json");
        Files.copy(NotifyConfigTest.example(), file);
        NotifySettingsPanel p = new NotifySettingsPanel(null, file);
        assertFalse(p.telegramEnabled.isSelected());
        assertTrue("secret fields are masked", p.botToken.getEchoChar() != 0 && p.webhookUrl.getEchoChar() != 0);
        assertEquals("REPLACE_WITH_CHAT_ID", p.chatId.getText());
        assertFalse("no notifier, no test", p.test.isEnabled());
        assertTrue(p.status.getText(), p.status.getText().startsWith("telegram: off; slack: off"));

        p.showTelegram.doClick();
        assertEquals(0, p.botToken.getEchoChar());
        p.showTelegram.doClick();
        assertTrue(p.botToken.getEchoChar() != 0);

        p.telegramEnabled.setSelected(true);
        p.botToken.setText(NotifyConfigTest.TOKEN);
        p.chatId.setText(NotifyConfigTest.CHAT);
        p.progress.setValue(20);
        p.alarm.setSelected(false);
        p.save();
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        NotifyConfig saved = NotifyConfig.load(file);
        assertEquals(NotifyConfig.SinkState.READY, saved.telegramState());
        assertEquals(NotifyConfig.SinkState.OFF, saved.slackState());
        assertEquals(20, saved.progressEveryPercent);
        assertFalse(saved.alarm);
        assertTrue(p.status.getText(), p.status.getText().startsWith("Saved. telegram: ready"));
        assertFalse(p.status.getText().contains(NotifyConfigTest.TOKEN));
    }

    @Test
    public void rendersHeadlessly() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("notify.json");
        String json = Files.readString(NotifyConfigTest.example()).replaceFirst("\"enabled\": false", "\"enabled\": true");
        Files.writeString(file, json);
        NotifySettingsPanel p = new NotifySettingsPanel(null, file);
        Dimension d = p.getPreferredSize();
        p.setSize(d);
        layout(p);
        BufferedImage img = new BufferedImage(d.width * 2, d.height * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.scale(2, 2);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        p.printAll(g);
        g.dispose();
        assertTrue(d.width > 200 && d.height > 200);
        String out = System.getProperty(PNG_PROPERTY);
        if (out != null && !out.isBlank()) {
            Path png = Paths.get(out);
            Files.createDirectories(png.toAbsolutePath().getParent());
            ImageIO.write(img, "png", png.toFile());
        }
    }

    private static void layout(java.awt.Container c) {
        c.doLayout();
        for (java.awt.Component child : c.getComponents()) {
            if (child instanceof java.awt.Container cc) {
                layout(cc);
            }
        }
    }
}
