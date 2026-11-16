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
package com.willwinder.ugs.nbp.bridge.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;

/**
 * A padlock drawn in code (no image resources): closed and red when Claude access is locked, open and green when
 * it is unlocked. Scales to any size.
 */
public final class PadlockIcon implements Icon {
    static final Color LOCKED = new Color(0xC0392B);
    static final Color UNLOCKED = new Color(0x1E8449);

    private final boolean locked;
    private final int size;

    public PadlockIcon(boolean locked, int size) {
        this.locked = locked;
        this.size = size;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.translate(x, y);
            double s = size / 24.0;
            g2.scale(s, s);
            Color color = locked ? LOCKED : UNLOCKED;
            if (c != null && !c.isEnabled()) {
                color = Color.GRAY;
            }
            g2.setColor(color);
            g2.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            // shackle: closed sits in the body; open is lifted and swung to the left
            if (locked) {
                g2.draw(new Arc2D.Double(7, 3, 10, 10, 0, 180, Arc2D.OPEN));
                g2.draw(new Line2D.Double(7, 8, 7, 11));
                g2.draw(new Line2D.Double(17, 8, 17, 11));
            } else {
                g2.draw(new Arc2D.Double(1.5, 1, 10, 10, 0, 180, Arc2D.OPEN));
                g2.draw(new Line2D.Double(1.5, 6, 1.5, 9));
                g2.draw(new Line2D.Double(11.5, 6, 11.5, 11));
            }
            g2.fill(new RoundRectangle2D.Double(4, 11, 16, 11, 3, 3));
            g2.setColor(Color.WHITE);
            g2.fill(new java.awt.geom.Ellipse2D.Double(10.5, 14, 3, 3));
            g2.fill(new java.awt.geom.Rectangle2D.Double(11.3, 16, 1.4, 3.5));
        } finally {
            g2.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return size;
    }

    @Override
    public int getIconHeight() {
        return size;
    }
}
