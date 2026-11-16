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

import com.willwinder.ugs.nbp.bridge.BridgeLock;
import com.willwinder.ugs.nbp.bridge.BridgeService;
import com.willwinder.ugs.nbp.lib.services.LocalizingService;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionReferences;
import org.openide.awt.ActionRegistration;
import org.openide.util.actions.Presenter;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.event.ActionEvent;

/**
 * The operator's MCP lock (SPEC §4.3) in the UGS chrome: a padlock toggle in its own toolbar ("Claude"), a check
 * box item <em>Machine → Lock Claude access</em> and the shortcut Cmd/Ctrl+Shift+L. All three show and flip the
 * same {@link BridgeLock}; the bridge panel mirrors it.
 */
@ActionID(category = LocalizingService.CATEGORY_MACHINE, id = LockClaudeAction.ID)
@ActionRegistration(displayName = "Lock Claude access", lazy = false)
@ActionReferences({
        @ActionReference(path = "Toolbars/Claude", position = 10),
        @ActionReference(path = LocalizingService.MENU_MACHINE, position = 1990, separatorBefore = 1985),
        @ActionReference(path = "Shortcuts", name = "DS-L")
})
public final class LockClaudeAction extends AbstractAction implements Presenter.Toolbar, Presenter.Menu, Presenter.Popup {
    public static final String ID = "com.willwinder.ugs.nbp.bridge.ui.LockClaudeAction";
    static final String TEXT = "Lock Claude access";

    public LockClaudeAction() {
        putValue(NAME, TEXT);
        putValue("menuText", TEXT);
        BridgeService.lock().addListener(s -> SwingUtilities.invokeLater(this::refresh));
        refresh();
    }

    private void refresh() {
        boolean locked = BridgeService.lock().isLocked();
        putValue(Action.SELECTED_KEY, locked);
        putValue(SMALL_ICON, new PadlockIcon(locked, 16));
        putValue(LARGE_ICON_KEY, new PadlockIcon(locked, 24));
        putValue(SHORT_DESCRIPTION, tooltip(locked));
    }

    static String tooltip(boolean locked) {
        return locked ? "Claude access is LOCKED: Claude gets nothing, not even status. Click to unlock (Observe/Query)."
                : "Claude access is UNLOCKED. Click to lock: refuses every Claude request and drops its queued commands.";
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        BridgeLock lock = BridgeService.lock();
        if (lock.isLocked()) {
            lock.unlock();
        } else {
            lock.lock();
        }
    }

    @Override
    public Component getToolbarPresenter() {
        JToggleButton b = new JToggleButton();
        b.setFocusable(false);
        Runnable update = () -> {
            boolean locked = BridgeService.lock().isLocked();
            b.setSelected(locked);
            b.setIcon(new PadlockIcon(locked, 24));
            b.setText(locked ? "Claude locked" : "Claude open");
            b.setToolTipText(tooltip(locked));
        };
        b.addActionListener(this);
        BridgeService.lock().addListener(s -> SwingUtilities.invokeLater(update));
        update.run();
        return b;
    }

    @Override
    public JMenuItem getMenuPresenter() {
        JCheckBoxMenuItem item = new JCheckBoxMenuItem(this);
        item.setIcon(null);
        item.setText(TEXT);
        return item;
    }

    @Override
    public JMenuItem getPopupPresenter() {
        return getMenuPresenter();
    }
}
