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

import com.willwinder.ugs.nbp.bridge.BridgeService;
import com.willwinder.ugs.nbp.bridge.PendantWatch;
import com.willwinder.ugs.nbp.bridge.api.ApiHost;
import com.willwinder.ugs.nbp.bridge.api.Authority;
import com.willwinder.ugs.nbp.bridge.api.Level;
import com.willwinder.ugs.nbp.bridge.log.LogRecord;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The operator's authority controls in the Claude Bridge panel (SPEC §4.3): Observe / Query / Motion / Stream,
 * the Motion expiry countdown, Claude's pending request, the API address with "Open 3D view", and the stock
 * pendant warning (§4.5). Only the operator can change anything here; the API can't.
 */
public final class AuthorityPanel extends JPanel {
    private static final Logger LOGGER = Logger.getLogger(AuthorityPanel.class.getName());
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final Map<Level, String> HELP = Map.of(
            Level.OBSERVE, "read status, console, log, settings, file",
            Level.QUERY, "also ?, $$, $#, $G, $I, $N",
            Level.MOTION, "also moves, jog, probe, offsets ($H/$X still ask you); expires after 15 min unused",
            Level.STREAM, "also run a file (you confirm each file)");

    private final Map<Level, JRadioButton> radios = new EnumMap<>(Level.class);
    private final JLabel stateLabel = new JLabel();
    private final JLabel requestLabel = new JLabel();
    private final JButton dismiss = new JButton("Dismiss");
    private final JPanel requestRow = new JPanel(new java.awt.BorderLayout(0, 2));
    private final JLabel apiLabel = new JLabel();
    private final JButton openView = new JButton("Open 3D view");
    private final JLabel pendantLabel = new JLabel();
    private final Timer timer = new Timer(1000, e -> refresh());
    private final Authority.Listener authorityListener = (c, s) -> SwingUtilities.invokeLater(this::refresh);
    private final java.util.function.Consumer<PendantWatch.State> pendantListener = s -> SwingUtilities.invokeLater(this::refresh);
    private Authority attached;
    private PendantWatch attachedPendant;
    private boolean updating;

    public AuthorityPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        JLabel title = new JLabel("Claude's authority");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        add(left(title));
        ButtonGroup group = new ButtonGroup();
        for (Level l : Level.values()) {
            JRadioButton r = new JRadioButton("<html><b>" + l.label() + "</b> &nbsp;<span style='color:gray'>"
                    + HELP.get(l) + "</span></html>");
            r.addActionListener(e -> choose(l));
            group.add(r);
            radios.put(l, r);
            add(left(r));
        }
        add(left(stateLabel));
        requestRow.add(requestLabel, java.awt.BorderLayout.CENTER);
        JPanel dismissRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        dismissRow.add(dismiss);
        requestRow.add(dismissRow, java.awt.BorderLayout.SOUTH);
        requestRow.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        dismiss.addActionListener(e -> {
            Authority a = BridgeService.authority();
            if (a != null) {
                a.dismissRequest();
            }
        });
        add(left(requestRow));
        add(Box.createVerticalStrut(6));
        JPanel apiRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        apiRow.add(apiLabel);
        apiRow.add(openView);
        openView.addActionListener(e -> openViewer());
        add(left(apiRow));
        pendantLabel.setForeground(new Color(0xC0392B));
        add(left(pendantLabel));
        add(Box.createVerticalGlue());
    }

    private static Component left(java.awt.Component c) {
        if (c instanceof javax.swing.JComponent j) {
            j.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        return c;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        timer.start();
        refresh();
    }

    @Override
    public void removeNotify() {
        timer.stop();
        if (attached != null) {
            attached.removeListener(authorityListener);
            attached = null;
        }
        if (attachedPendant != null) {
            attachedPendant.removeListener(pendantListener);
            attachedPendant = null;
        }
        super.removeNotify();
    }

    private void choose(Level l) {
        if (updating) {
            return;
        }
        Authority a = BridgeService.authority();
        if (a != null) {
            a.setLevel(l, LogRecord.Src.OPERATOR);
        }
        refresh();
    }

    private void openViewer() {
        ApiHost api = BridgeService.api();
        if (api == null) {
            return;
        }
        try {
            Desktop.getDesktop().browse(URI.create(api.viewerUrl()));
        } catch (Exception ex) {
            LOGGER.warning(() -> "could not open the browser: " + ex.getMessage());
        }
    }

    void refresh() {
        Authority a = BridgeService.authority();
        if (a != attached) {
            if (attached != null) {
                attached.removeListener(authorityListener);
            }
            attached = a;
            if (a != null) {
                a.addListener(authorityListener);
            }
        }
        PendantWatch pw = BridgeService.pendantWatch();
        if (pw != attachedPendant) {
            if (attachedPendant != null) {
                attachedPendant.removeListener(pendantListener);
            }
            attachedPendant = pw;
            if (pw != null) {
                pw.addListener(pendantListener);
            }
        }
        if (a == null) {
            radios.values().forEach(r -> r.setEnabled(false));
            stateLabel.setText("Bridge not running");
            requestRow.setVisible(false);
            return;
        }
        Authority.Snapshot s = a.snapshot();
        updating = true;
        try {
            radios.forEach((l, r) -> {
                r.setEnabled(!s.locked());
                r.setSelected(l == s.level());
            });
        } finally {
            updating = false;
        }
        if (s.locked()) {
            stateLabel.setText("<html><span style='color:#C0392B'><b>Locked</b></span>: Claude can read status and logs but can't send anything."
                    + " Unlock with the padlock (toolbar, Machine menu, Cmd/Ctrl+Shift+L).</html>");
        } else if (s.motionExpiresAt() != null) {
            long left = Math.max(0, Duration.between(Instant.now(), s.motionExpiresAt()).toSeconds());
            stateLabel.setText(String.format("<html><span style='color:#B9770E'><b>%s armed</b></span>: expires at %s"
                            + " (%d:%02d) unless Claude uses it; also on alarm, reset or disconnect.</html>",
                    s.level().label(), HM.format(s.motionExpiresAt()), left / 60, left % 60));
        } else {
            stateLabel.setText("<html><span style='color:#1E8449'><b>Unlocked</b></span>: " + s.level().label()
                    + ". Hold, jog cancel and reset are always allowed.</html>");
        }
        if (s.pendingRequest() != null) {
            requestLabel.setText("<html><div style='width:230px'><b>Claude asks for " + s.pendingRequest().level().label() + "</b> ("
                    + HM.format(s.pendingRequest().at()) + "): " + escape(s.pendingRequest().reason()) + "</html>");
            requestRow.setVisible(true);
        } else {
            requestRow.setVisible(false);
        }
        ApiHost api = BridgeService.api();
        if (api != null && api.port() > 0) {
            apiLabel.setText("API 127.0.0.1:" + api.port());
            openView.setEnabled(true);
        } else {
            apiLabel.setText("API not running");
            openView.setEnabled(false);
        }
        String warn = pw == null ? null : pw.state().warning();
        pendantLabel.setText(warn == null ? "" : "<html><b>Warning:</b> " + escape(warn) + "</html>");
        pendantLabel.setVisible(warn != null);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
