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

import com.willwinder.ugs.nbp.bridge.ui.AuthorityPanel;
import com.willwinder.ugs.nbp.bridge.ui.PadlockIcon;
import com.willwinder.ugs.nbp.lib.Mode;
import com.willwinder.ugs.nbp.lib.services.LocalizingService;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.windows.TopComponent;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.function.Consumer;

/**
 * The bridge panel. W0 skeleton: shows and toggles the MCP lock. Authority levels, notifications,
 * job checklist and resume UI are added by later work packages.
 */
@TopComponent.Description(preferredID = "BridgeTopComponent")
@TopComponent.Registration(mode = Mode.LEFT_BOTTOM, openAtStartup = true)
@ActionID(category = LocalizingService.CATEGORY_WINDOW, id = "com.willwinder.ugs.nbp.bridge.BridgeTopComponent")
@ActionReference(path = LocalizingService.MENU_WINDOW)
@TopComponent.OpenActionRegistration(displayName = "Claude Bridge", preferredID = "BridgeTopComponent")
public final class BridgeTopComponent extends TopComponent {
    private final JToggleButton lockButton = new JToggleButton();
    private final JLabel stateLabel = new JLabel();
    private final javax.swing.JCheckBox toolChangeBox = new javax.swing.JCheckBox("BitSetter tool changes");
    private final Consumer<BridgeLock.State> lockListener = s -> SwingUtilities.invokeLater(this::refresh);
    /** The panel can open before the job runtime has started: refresh until it has (the option is on by default, D20). */
    private final javax.swing.Timer runtimeWait = new javax.swing.Timer(1000, e -> refresh());

    public BridgeTopComponent() {
        setName("Claude Bridge");
        setLayout(new BorderLayout());
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row.add(lockButton);
        row.add(stateLabel);
        row.add(toolChangeBox);
        add(row, BorderLayout.NORTH);
        add(new javax.swing.JScrollPane(new AuthorityPanel()), BorderLayout.CENTER); // package E/F: levels, expiry, Claude's requests, API, pendant
        toolChangeBox.setToolTipText("M6 parks, asks for the tool, measures it on the BitSetter and sets Z "
                + "(replaces UGS's own tool-change handling while on; on by default)");
        toolChangeBox.addActionListener(e -> {
            com.willwinder.ugs.nbp.bridge.runtime.JobRuntime rt = com.willwinder.ugs.nbp.bridge.runtime.JobRuntime.current();
            if (rt != null) {
                rt.setToolChangesEnabled(toolChangeBox.isSelected());
            }
        });
        lockButton.addActionListener(e -> {
            BridgeLock lock = BridgeService.lock();
            if (lock.isLocked()) {
                lock.unlock();
            } else {
                lock.lock();
            }
        });
    }

    @Override
    public void componentOpened() {
        BridgeService.lock().addListener(lockListener);
        refresh();
    }

    @Override
    public void componentClosed() {
        BridgeService.lock().removeListener(lockListener);
        runtimeWait.stop();
    }

    private void refresh() {
        boolean locked = BridgeService.lock().isLocked();
        lockButton.setSelected(!locked);
        lockButton.setText(locked ? "Unlock Claude access" : "Lock Claude access");
        lockButton.setIcon(new PadlockIcon(locked, 16));
        stateLabel.setText(locked ? "MCP: locked" : "MCP: unlocked");
        com.willwinder.ugs.nbp.bridge.runtime.JobRuntime rt = com.willwinder.ugs.nbp.bridge.runtime.JobRuntime.current();
        toolChangeBox.setEnabled(rt != null);
        toolChangeBox.setSelected(rt != null && rt.toolChangesEnabled());
        if (rt == null && isOpened()) {
            runtimeWait.start();
        } else {
            runtimeWait.stop();
        }
    }
}
