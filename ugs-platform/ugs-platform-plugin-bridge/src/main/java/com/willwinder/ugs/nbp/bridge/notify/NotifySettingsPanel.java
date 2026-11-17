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

import com.willwinder.ugs.nbp.bridge.log.BridgeHome;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Settings for Telegram/Slack notifications: sinks with masked secret fields, trigger toggles, Reload, Save
 * (notify.json, mode 0600) and Send test.
 * <p>
 * Hook for the bridge panel (one line): {@code add(new NotifySettingsPanel(BridgeService.notifier()));}
 * With a null notifier it edits {@code <bridge home>/notify.json} and "Send test" is disabled.
 */
public final class NotifySettingsPanel extends JPanel {
    private final BridgeNotifier notifier;
    private final Path file;

    final JCheckBox telegramEnabled = new JCheckBox("Telegram");
    final JPasswordField botToken = new JPasswordField(28);
    final JTextField chatId = new JTextField(14);
    final JCheckBox showTelegram = new JCheckBox("show");
    final JCheckBox telegramCommands = new JCheckBox("Answer status/eta/tools/log from this chat");
    final JCheckBox telegramPause = new JCheckBox("Allow pause (feed hold)");
    final JCheckBox slackEnabled = new JCheckBox("Slack");
    final JPasswordField webhookUrl = new JPasswordField(28);
    final JCheckBox showSlack = new JCheckBox("show");

    final JCheckBox streamFinished = new JCheckBox("Job finished");
    final JCheckBox streamError = new JCheckBox("Stream stopped on error");
    final JCheckBox alarm = new JCheckBox("ALARM");
    final JCheckBox toolChange = new JCheckBox("Tool change waiting");
    final JCheckBox connectionLost = new JCheckBox("Connection lost");
    final JCheckBox claudeAuthority = new JCheckBox("Claude asks for authority");
    final JCheckBox claudeMessage = new JCheckBox("Claude's messages");
    final JSpinner feedHold = new JSpinner(new SpinnerNumberModel(60, 0, 86_400, 10));
    final JSpinner progress = new JSpinner(new SpinnerNumberModel(0, 0, 100, 5));
    final JSpinner rateLimit = new JSpinner(new SpinnerNumberModel(6, 1, 60, 1));

    final JButton reload = new JButton("Reload");
    final JButton save = new JButton("Save");
    final JButton test = new JButton("Send test");
    final JLabel status = new JLabel(" ");

    private NotifyConfig loaded;

    public NotifySettingsPanel(BridgeNotifier notifier) {
        this(notifier, notifier != null ? notifier.configFile() : BridgeHome.resolve().resolve(NotifyConfig.FILE_NAME));
    }

    NotifySettingsPanel(BridgeNotifier notifier, Path file) {
        super(new GridBagLayout());
        this.notifier = notifier;
        this.file = file;
        char echo = botToken.getEchoChar();
        showTelegram.addActionListener(e -> botToken.setEchoChar(showTelegram.isSelected() ? (char) 0 : echo));
        showSlack.addActionListener(e -> webhookUrl.setEchoChar(showSlack.isSelected() ? (char) 0 : echo));
        botToken.setToolTipText("From @BotFather: <digits>:<secret>");
        chatId.setToolTipText("Your chat id (a number), from getUpdates after messaging the bot");
        webhookUrl.setToolTipText("Slack incoming webhook: https://hooks.slack.com/services/…");
        feedHold.setToolTipText("0 = off");
        progress.setToolTipText("0 = off");
        test.setToolTipText("Sends a test message with the saved notify.json (Save first)");
        layoutComponents();
        reload.addActionListener(e -> load(true));
        save.addActionListener(e -> save());
        test.addActionListener(e -> sendTest());
        test.setEnabled(notifier != null);
        load(false);
    }

    private void layoutComponents() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int y = 0;

        JPanel sinks = new JPanel(new GridBagLayout());
        sinks.setBorder(BorderFactory.createTitledBorder("Send to"));
        y = row(sinks, y, telegramEnabled, null, null);
        y = row(sinks, y, new JLabel("Bot token"), botToken, showTelegram);
        y = row(sinks, y, new JLabel("Chat id"), chatId, null);
        y = row(sinks, y, telegramCommands, telegramPause, null);
        y = row(sinks, y, slackEnabled, null, null);
        row(sinks, y, new JLabel("Webhook URL"), webhookUrl, showSlack);

        JPanel triggers = new JPanel(new GridBagLayout());
        triggers.setBorder(BorderFactory.createTitledBorder("Notify on"));
        int t = 0;
        t = row(triggers, t, streamFinished, streamError, null);
        t = row(triggers, t, alarm, toolChange, null);
        t = row(triggers, t, connectionLost, claudeAuthority, null);
        t = row(triggers, t, claudeMessage, null, null);
        t = row(triggers, t, new JLabel("Feed hold longer than (s, 0 = off)"), feedHold, null);
        t = row(triggers, t, new JLabel("Progress every (%, 0 = off)"), progress, null);
        row(triggers, t, new JLabel("At most per minute"), rateLimit, null);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.add(reload);
        buttons.add(save);
        buttons.add(test);

        c.gridx = 0;
        c.weightx = 1;
        c.gridy = 0;
        add(sinks, c);
        c.gridy = 1;
        add(triggers, c);
        c.gridy = 2;
        add(buttons, c);
        c.gridy = 3;
        add(status, c);
        c.gridy = 4;
        c.weighty = 1;
        add(new JLabel(" "), c);
    }

    private static int row(JPanel p, int y, java.awt.Component a, java.awt.Component b, java.awt.Component d) {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(1, 4, 1, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridy = y;
        c.gridx = 0;
        p.add(a, c);
        if (b != null) {
            c.gridx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            p.add(b, c);
        }
        if (d != null) {
            c.gridx = 2;
            c.fill = GridBagConstraints.NONE;
            c.weightx = 0;
            p.add(d, c);
        }
        return y + 1;
    }

    /** Fills the fields from notify.json (through the notifier if there is one). */
    void load(boolean forced) {
        NotifyConfig cfg = notifier != null ? (forced ? notifier.reload() : notifier.config()) : NotifyConfig.load(file);
        loaded = cfg;
        telegramEnabled.setSelected(cfg.telegramEnabled);
        botToken.setText(cfg.botToken);
        chatId.setText(cfg.chatId);
        telegramCommands.setSelected(cfg.telegramCommands);
        telegramPause.setSelected(cfg.telegramAllowPause);
        slackEnabled.setSelected(cfg.slackEnabled);
        webhookUrl.setText(cfg.webhookUrl);
        streamFinished.setSelected(cfg.streamFinished);
        streamError.setSelected(cfg.streamError);
        alarm.setSelected(cfg.alarm);
        toolChange.setSelected(cfg.toolChangeWaiting);
        connectionLost.setSelected(cfg.connectionLost);
        claudeAuthority.setSelected(cfg.claudeAuthorityRequest);
        claudeMessage.setSelected(cfg.claudeMessage);
        feedHold.setValue(cfg.feedHoldSeconds);
        progress.setValue(cfg.progressEveryPercent);
        rateLimit.setValue(cfg.rateLimitPerMinute);
        status.setText(cfg.summary());
    }

    /** The config as currently edited (not saved). */
    NotifyConfig edited() {
        NotifyConfig.Builder b = (loaded != null ? loaded : NotifyConfig.defaults()).toBuilder();
        b.telegramEnabled = telegramEnabled.isSelected();
        b.botToken = new String(botToken.getPassword()).trim();
        b.chatId = chatId.getText().trim();
        b.telegramCommands = telegramCommands.isSelected();
        b.telegramAllowPause = telegramPause.isSelected();
        b.slackEnabled = slackEnabled.isSelected();
        b.webhookUrl = new String(webhookUrl.getPassword()).trim();
        b.streamFinished = streamFinished.isSelected();
        b.streamError = streamError.isSelected();
        b.alarm = alarm.isSelected();
        b.toolChangeWaiting = toolChange.isSelected();
        b.connectionLost = connectionLost.isSelected();
        b.claudeAuthorityRequest = claudeAuthority.isSelected();
        b.claudeMessage = claudeMessage.isSelected();
        b.feedHoldSeconds = (Integer) feedHold.getValue();
        b.progressEveryPercent = (Integer) progress.getValue();
        b.rateLimitPerMinute = (Integer) rateLimit.getValue();
        return b.build();
    }

    /** Writes notify.json (0600) and reloads the notifier. */
    void save() {
        NotifyConfig cfg = edited();
        try {
            cfg.save(file);
            loaded = notifier != null ? notifier.reload() : NotifyConfig.load(file);
            status.setText("Saved. " + loaded.summary());
        } catch (IOException e) {
            status.setText("Could not save " + file.getFileName() + ": " + e.getClass().getSimpleName());
        }
    }

    private void sendTest() {
        if (notifier == null) {
            return;
        }
        test.setEnabled(false);
        status.setText("Sending test…");
        notifier.sendTest().whenComplete((res, err) -> SwingUtilities.invokeLater(() -> {
            test.setEnabled(true);
            status.setText(err != null ? "Test failed: " + err.getClass().getSimpleName() : "Test: " + format(res));
        }));
    }

    static String format(Map<String, String> res) {
        return res.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(" · "));
    }
}
