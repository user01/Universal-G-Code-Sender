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
package com.willwinder.ugs.nbp.bridge.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.willwinder.ugs.nbp.bridge.log.FileFingerprint;
import com.willwinder.ugs.nbp.bridge.log.LogRecord.Kind;
import com.willwinder.ugs.nbp.bridge.log.LogRecord.Src;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;
import com.willwinder.universalgcodesender.IController;
import com.willwinder.universalgcodesender.communicator.ICommunicator;
import com.willwinder.universalgcodesender.communicator.ICommunicatorListener;
import com.willwinder.universalgcodesender.connection.ConnectionDriver;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.MessageListener;
import com.willwinder.universalgcodesender.listeners.MessageType;
import com.willwinder.universalgcodesender.listeners.UGSEventListener;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UGSEvent;
import com.willwinder.universalgcodesender.model.events.AlarmEvent;
import com.willwinder.universalgcodesender.model.events.CommandEvent;
import com.willwinder.universalgcodesender.model.events.ControllerStateEvent;
import com.willwinder.universalgcodesender.model.events.ControllerStatusEvent;
import com.willwinder.universalgcodesender.model.events.FileState;
import com.willwinder.universalgcodesender.model.events.FileStateEvent;
import com.willwinder.universalgcodesender.model.events.FirmwareSettingEvent;
import com.willwinder.universalgcodesender.model.events.ProbeEvent;
import com.willwinder.universalgcodesender.model.events.SettingChangedEvent;
import com.willwinder.universalgcodesender.model.events.StreamEvent;
import com.willwinder.universalgcodesender.model.events.StreamEventType;
import com.willwinder.universalgcodesender.services.interceptor.InterceptorState;
import com.willwinder.universalgcodesender.services.interceptor.InterceptorStateEvent;
import com.willwinder.universalgcodesender.types.GcodeCommand;
import com.willwinder.universalgcodesender.utils.Settings;
import com.willwinder.universalgcodesender.utils.Version;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Captures everything in SPEC §4.1 into the {@link SessionLog}.
 * <p>
 * Sources and the records they produce (see bridge/docs/log-format.md):
 * <ul>
 *     <li>{@link ICommunicatorListener} of the connected controller's communicator: {@code tx} for every
 *     line written ({@code commandSent}), {@code rx} for every line read except {@code <...>} reports, and
 *     {@code status} built from the raw {@code <...>} reports (de-duplicated, 1 s heartbeat). These all
 *     arrive on UGS's communicator event thread, so their relative order is the wire order.</li>
 *     <li>{@link MessageListener}: {@code message} (VERBOSE echoes of {@code <...>} reports are dropped).</li>
 *     <li>{@link UGSEventListener}: {@code event} (alarm, controller_state, stream, file_state, probe,
 *     setting, interceptor), {@code command} (lifecycle), {@code file} (loaded / stream start / end),
 *     {@code session} (connect / disconnect) and, for firmware without {@code <...>} reports,
 *     {@code status} from {@link ControllerStatusEvent}.</li>
 * </ul>
 * Attribution ({@code src}): commands registered in {@link CommandOrigins} use their registered source
 * (claude / bridge); file lines, interceptor commands, UGS's own queries and anything sent while the
 * controller is initializing are {@code ugs}; other generated commands are {@code operator}. {@code rx}
 * lines take the source of the command they answer, or {@code controller} if no command is waiting.
 * <p>
 * Real-time bytes written with {@code ICommunicator.sendByteImmediately} arrive through
 * {@link #realtimeByteSent} (upstream patch P3) and become {@code tx} records with {@code realtime};
 * {@code ?} polls are only counted (they are implied by {@code status}). A {@code ?} that UGS sends as a
 * whole line ({@code GetStatusCommand} at connect) is a line on the wire: the controller answers it with a
 * report and {@code ok}, so it gets a {@code tx} record and its {@code ok} is attributed to it (no
 * {@code command} records: UGS completes it on the report and calls it not ok).
 * <p>
 * {@code rx} attribution relies on upstream patch P6: {@code commandSent} is dispatched before the line is
 * written, so it always precedes the answer in the communicator's event order. An answer with nothing
 * waiting is therefore unsolicited (or answers a command sent before the bridge attached). Events UGS
 * discards when it resets the communicator still reach the capture (patch P4,
 * {@link #receivesDiscardedEvents}).
 */
public final class Capture implements UGSEventListener, MessageListener, ICommunicatorListener {
    private static final Logger LOGGER = Logger.getLogger(Capture.class.getName());
    private static final int MAX_IN_FLIGHT = 1000;
    private static final long REALTIME_ATTRIBUTION_NANOS = 2_000_000_000L;
    private static final Pattern CONSOLE_ECHO = Pattern.compile("^(\\[\\w+] )?>>>( |$)");

    private final SessionLog log;
    private final CommandOrigins origins;
    private final LongSupplier nanoTime;
    private final String bridgeVersion;
    private final GrblStatusParser parser = new GrblStatusParser();
    private final StatusFilter statusFilter = new StatusFilter();

    // all fields below guarded by this
    private BackendAPI backend;
    private ICommunicator communicator;
    private IController controller;
    private boolean connected;
    private ControllerState controllerState = ControllerState.DISCONNECTED;
    private boolean sawRawStatus;
    private final Deque<InFlight> inFlight = new ArrayDeque<>();
    private final Deque<ExpectedRealtime> expectedRealtime = new ArrayDeque<>();
    private long statusPolls;
    private InterceptorState interceptorState = InterceptorState.INACTIVE;
    private FileFingerprint streamFile;
    private String nextStreamSrc;
    private String streamSrc = Src.OPERATOR;
    private ScheduledExecutorService ticker;

    public Capture(SessionLog log, CommandOrigins origins, String bridgeVersion) {
        this(log, origins, bridgeVersion, System::nanoTime);
    }

    Capture(SessionLog log, CommandOrigins origins, String bridgeVersion, LongSupplier nanoTime) {
        this.log = log;
        this.origins = origins;
        this.bridgeVersion = bridgeVersion;
        this.nanoTime = nanoTime;
    }

    public CommandOrigins origins() {
        return origins;
    }

    /** Subscribes to the backend; if it is already connected, starts a session right away. */
    public synchronized void attach(BackendAPI backend) {
        this.backend = backend;
        backend.addUGSEventListener(this);
        backend.addMessageListener(this);
        if (backend.isConnected()) {
            onConnect();
        }
    }

    public synchronized void detach() {
        stopHeartbeat();
        detachCommunicator();
        if (backend != null) {
            backend.removeUGSEventListener(this);
            backend.removeMessageListener(this);
            backend = null;
        }
    }

    /** Starts the 1 s status heartbeat check (every 250 ms). */
    public synchronized void startHeartbeat() {
        if (ticker == null) {
            ticker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ugs-bridge-status-heartbeat");
                t.setDaemon(true);
                return t;
            });
            ticker.scheduleAtFixedRate(this::tick, 250, 250, TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void stopHeartbeat() {
        if (ticker != null) {
            ticker.shutdownNow();
            ticker = null;
        }
    }

    /** Emits a status heartbeat if one is due. */
    public synchronized void tick() {
        JsonObject hb = statusFilter.tick(nanoTime.getAsLong());
        if (hb != null) {
            log.append(Kind.STATUS, Src.CONTROLLER, hb);
        }
    }

    /**
     * Hook for the API: the next stream start is attributed to {@code src} (e.g. claude) instead of operator.
     */
    public synchronized void attributeNextStream(String src) {
        this.nextStreamSrc = src;
    }

    /**
     * Hook for callers that are about to write a real-time byte themselves (the API's hold, resume, jog
     * cancel, reset): the next time {@code b} is seen on the wire (within 2 s) its {@code tx} record gets
     * {@code src} and {@code cmdId} instead of the default ({@code operator}).
     */
    public synchronized void attributeRealtime(byte b, String src, String cmdId) {
        long now = nanoTime.getAsLong();
        expectedRealtime.removeIf(e -> e.deadline - now < 0);
        expectedRealtime.addLast(new ExpectedRealtime(b, src, cmdId, now + REALTIME_ATTRIBUTION_NANOS));
    }

    /** Number of real-time {@code ?} polls seen since the bridge attached (they are not logged as tx). */
    public synchronized long statusPolls() {
        return statusPolls;
    }

    // ---------------------------------------------------------------- session

    private void onConnect() {
        connected = true;
        String port = null;
        String driver = null;
        String firmware = null;
        String slug = "unknown";
        try {
            Settings s = backend.getSettings();
            if (s != null) {
                ConnectionDriver cd = s.getConnectionDriver();
                driver = cd == null ? null : cd.name().toLowerCase(Locale.ROOT);
                firmware = s.getFirmwareVersion();
                if (cd == ConnectionDriver.TCP || cd == ConnectionDriver.WS) {
                    port = s.getPort() + ":" + s.getPortRate();
                    slug = driver + "_" + s.getPort() + "_" + s.getPortRate();
                } else {
                    port = s.getPort();
                    slug = port;
                }
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not read connection settings", e);
        }
        log.openSession(SessionLog.portSlug(slug));
        JsonObject d = sessionData("connect");
        d.addProperty("port", port);
        d.addProperty("driver", driver);
        d.addProperty("firmware", firmware);
        log.append(Kind.SESSION, Src.OPERATOR, d);

        parser.reset();
        statusFilter.reset();
        sawRawStatus = false;
        inFlight.clear();
        expectedRealtime.clear();
        attachCommunicator();
    }

    private void onDisconnect(String reason) {
        connected = false;
        JsonObject d = sessionData("disconnect");
        if (reason != null) {
            d.addProperty("reason", reason);
        }
        log.append(Kind.SESSION, "connection_closed".equals(reason) ? Src.CONTROLLER : Src.OPERATOR, d);
        detachCommunicator();
        statusFilter.reset();
        inFlight.clear();
    }

    /** Data for a {@code session} record with the versions filled in. */
    public JsonObject sessionData(String event) {
        JsonObject d = new JsonObject();
        d.addProperty("event", event);
        d.addProperty("ugs_version", ugsVersion());
        d.addProperty("bridge_version", bridgeVersion);
        return d;
    }

    static String ugsVersion() {
        try {
            String v = Version.getVersionString();
            return v == null ? "unknown" : v;
        } catch (RuntimeException | Error e) {
            return "unknown";
        }
    }

    private void attachCommunicator() {
        detachCommunicator();
        try {
            controller = backend.getController();
            communicator = controller == null ? null : controller.getCommunicator();
            if (communicator != null) {
                communicator.addListener(this);
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not attach to the communicator", e);
        }
    }

    private void detachCommunicator() {
        if (communicator != null) {
            try {
                communicator.removeListener(this);
            } catch (RuntimeException e) {
                LOGGER.log(Level.FINE, "Could not detach from the communicator", e);
            }
        }
        communicator = null;
        controller = null;
    }

    /** Re-attaches if UGS replaced the controller without a state change we noticed. */
    private void ensureCommunicator() {
        if (!connected || backend == null) {
            return;
        }
        IController current = backend.getController();
        if (current != null && current != controller) {
            attachCommunicator();
        }
    }

    // ---------------------------------------------------------------- communicator (wire)

    @Override
    public synchronized void rawResponseListener(String response) {
        if (response == null) {
            return;
        }
        String line = response.strip();
        if (line.isEmpty()) {
            return;
        }
        if (GrblStatusParser.isStatusReport(line)) {
            JsonObject st = parser.parse(line);
            if (st != null) {
                sawRawStatus = true;
                String units = reportingUnits();
                if (units != null) {
                    st.addProperty("units", units);
                }
                JsonObject out = statusFilter.offer(st, nanoTime.getAsLong());
                if (out != null) {
                    log.append(Kind.STATUS, Src.CONTROLLER, out);
                }
            }
            return;
        }

        JsonObject d = new JsonObject();
        d.addProperty("line", line);
        String src = Src.CONTROLLER;
        if (RecordMapper.isWelcome(line)) {
            inFlight.clear(); // controller reset: nothing is waiting any more
        } else if (!inFlight.isEmpty()) {
            InFlight head = inFlight.peekFirst();
            d.addProperty("cmd_id", head.cmdId);
            src = head.src;
            if (RecordMapper.isAck(line)) {
                inFlight.removeFirst();
            }
        }
        log.append(Kind.RX, src, d);

        Integer alarm = RecordMapper.alarmCode(line);
        if (alarm != null) {
            // UGS's AlarmEvent only carries a coarse enum; the code comes from the wire
            JsonObject ev = event("alarm");
            ev.addProperty("code", alarm);
            ev.addProperty("line", line);
            log.append(Kind.EVENT, Src.CONTROLLER, ev);
        }
    }

    @Override
    public synchronized void commandSent(GcodeCommand command) {
        String line = command.getCommandString();
        Attribution a = attribute(command);
        JsonObject d = new JsonObject();
        d.addProperty("line", line);
        d.addProperty("cmd_id", a.cmdId);
        if (!command.isGenerated() && command.getCommandNumber() >= 0) {
            d.addProperty("file_line", command.getCommandNumber() + 1);
        }
        log.append(Kind.TX, a.src, d);

        inFlight.addLast(new InFlight(a.cmdId, a.src));
        while (inFlight.size() > MAX_IN_FLIGHT) {
            inFlight.removeFirst();
        }
        if (command.isGenerated() && !isStatusQuery(command)) {
            log.append(Kind.COMMAND, a.src, commandData(command, a, "sent"));
        }
    }

    /** Upstream patch P4: lines still queued when UGS resets the communicator are logged too. */
    @Override
    public boolean receivesDiscardedEvents() {
        return true;
    }

    @Override
    public synchronized void realtimeByteSent(byte b) {
        if (b == '?') {
            statusPolls++; // implied by the status records
            return;
        }
        int v = b & 0xff;
        String src = interceptorState != InterceptorState.INACTIVE ? Src.UGS : Src.OPERATOR;
        String cmdId = null;
        long now = nanoTime.getAsLong();
        for (var it = expectedRealtime.iterator(); it.hasNext(); ) {
            ExpectedRealtime e = it.next();
            if (e.deadline - now < 0) {
                it.remove();
            } else if (e.b == b) {
                it.remove();
                src = e.src;
                cmdId = e.cmdId;
                break;
            }
        }
        JsonObject d = new JsonObject();
        d.addProperty("line", v >= 0x21 && v < 0x7f ? String.valueOf((char) v) : String.format("0x%02x", v));
        if (cmdId != null) {
            d.addProperty("cmd_id", cmdId);
        }
        d.addProperty("realtime", String.format("0x%02x", v));
        log.append(Kind.TX, src, d);
    }

    @Override
    public void commandSkipped(GcodeCommand command) {
        // logged from CommandEvent COMMAND_SKIPPED
    }

    @Override
    public synchronized void communicatorPausedOnError() {
        JsonObject d = event("stream");
        d.addProperty("event", "paused_on_error");
        log.append(Kind.EVENT, Src.UGS, d);
    }

    @Override
    public synchronized void onConnectionClosed() {
        if (connected) {
            onDisconnect("connection_closed");
        }
    }

    private String reportingUnits() {
        try {
            IController c = controller;
            if (c != null && c.getFirmwareSettings() != null) {
                return RecordMapper.unitsName(c.getFirmwareSettings().getReportingUnits());
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINEST, "No reporting units", e);
        }
        return null;
    }

    // ---------------------------------------------------------------- messages

    @Override
    public synchronized void onMessage(MessageType messageType, String message) {
        if (message == null) {
            return;
        }
        String text = message.stripTrailing();
        if (GrblStatusParser.isStatusReport(text.strip())) {
            return; // UGS echoes status reports (VERBOSE, or INFO as a query's response); they are in status
        }
        if (isConsoleEcho(text)) {
            return; // D16: ">>> cmd" and the echoed "ok" duplicate the tx/rx records
        }
        JsonObject d = new JsonObject();
        d.addProperty("type", messageType == null ? "INFO" : messageType.name());
        d.addProperty("text", text);
        log.append(Kind.MESSAGE, Src.UGS, d);
    }

    // ---------------------------------------------------------------- UGS events

    @Override
    public synchronized void UGSEvent(UGSEvent evt) {
        try {
            dispatch(evt);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not capture " + evt.getClass().getSimpleName(), e);
        }
    }

    private void dispatch(UGSEvent evt) {
        if (evt instanceof ControllerStateEvent e) {
            onControllerState(e);
        } else if (evt instanceof ControllerStatusEvent e) {
            if (!sawRawStatus && connected && e.getStatus() != null) {
                JsonObject out = statusFilter.offer(RecordMapper.status(e.getStatus()), nanoTime.getAsLong());
                if (out != null) {
                    log.append(Kind.STATUS, Src.CONTROLLER, out);
                }
            }
        } else if (evt instanceof AlarmEvent e) {
            JsonObject d = event("alarm");
            d.addProperty("ugs_alarm", RecordMapper.lower(e.getAlarm()));
            log.append(Kind.EVENT, Src.CONTROLLER, d);
        } else if (evt instanceof CommandEvent e) {
            onCommandEvent(e);
        } else if (evt instanceof StreamEvent e) {
            onStream(e);
        } else if (evt instanceof FileStateEvent e) {
            onFileState(e);
        } else if (evt instanceof ProbeEvent e) {
            JsonObject d = event("probe");
            Position p = e.getProbePosition();
            d.add("position", RecordMapper.position(p));
            d.addProperty("units", "mm");
            d.addProperty("coordinates", "machine");
            log.append(Kind.EVENT, Src.CONTROLLER, d);
        } else if (evt instanceof FirmwareSettingEvent e) {
            JsonObject d = event("setting");
            d.addProperty("scope", "firmware");
            if (e.getFirmwareSetting() != null) {
                d.addProperty("key", e.getFirmwareSetting().getKey());
                d.addProperty("value", e.getFirmwareSetting().getValue());
            }
            log.append(Kind.EVENT, Src.CONTROLLER, d);
        } else if (evt instanceof SettingChangedEvent) {
            JsonObject d = event("setting");
            d.addProperty("scope", "ugs");
            log.append(Kind.EVENT, Src.OPERATOR, d);
        } else if (evt instanceof InterceptorStateEvent e) {
            onInterceptor(e);
        }
    }

    private void onControllerState(ControllerStateEvent e) {
        ControllerState state = e.getState();
        controllerState = state;
        boolean disconnected = state == ControllerState.DISCONNECTED;
        if (!disconnected && !connected) {
            onConnect();
        } else if (!disconnected) {
            ensureCommunicator();
        }
        JsonObject d = event("controller_state");
        d.addProperty("state", RecordMapper.lower(state));
        d.addProperty("previous", RecordMapper.lower(e.getPreviousState()));
        boolean synthetic = disconnected || state == ControllerState.CONNECTING;
        log.append(Kind.EVENT, synthetic ? Src.UGS : Src.CONTROLLER, d);
        if (disconnected && connected) {
            onDisconnect(null);
        }
    }

    private void onCommandEvent(CommandEvent e) {
        GcodeCommand cmd = e.getCommand();
        if (cmd == null || isStatusQuery(cmd)) {
            return; // whole-line '?' queries are fully described by tx/rx (UGS reports them as not ok)
        }
        Attribution a = attribute(cmd);
        switch (e.getCommandEventType()) {
            case COMMAND_SKIPPED -> {
                log.append(Kind.COMMAND, a.src, commandData(cmd, a, "skipped"));
                origins.forget(cmd);
            }
            case COMMAND_COMPLETE -> {
                String state = cmd.isOk() ? "ok" : "error";
                Integer alarm = null;
                Integer error = null;
                if (cmd.getResponse() != null) {
                    for (String l : cmd.getResponse().split("\n")) {
                        alarm = alarm != null ? alarm : RecordMapper.alarmCode(l);
                        error = error != null ? error : RecordMapper.errorCode(l);
                    }
                }
                if (!cmd.isOk() && alarm != null && error == null) {
                    state = "alarm";
                }
                // File lines are already fully described by tx/rx; only their failures get a command record.
                if (cmd.isGenerated() || !cmd.isOk()) {
                    JsonObject d = commandData(cmd, a, state);
                    if (error != null) {
                        d.addProperty("error_code", error);
                    }
                    if (alarm != null) {
                        d.addProperty("alarm_code", alarm);
                    }
                    d.add("response", responseLines(cmd));
                    log.append(Kind.COMMAND, a.src, d);
                }
                origins.forget(cmd);
            }
            default -> {
                // COMMAND_SENT is logged from the communicator listener, in wire order
            }
        }
    }

    private void onStream(StreamEvent e) {
        StreamEventType type = e.getType();
        String src;
        if (type == StreamEventType.STREAM_STARTED) {
            streamSrc = nextStreamSrc != null ? nextStreamSrc : Src.OPERATOR;
            nextStreamSrc = null;
            src = streamSrc;
        } else if (type == StreamEventType.STREAM_COMPLETE) {
            src = Src.UGS;
        } else {
            src = Src.OPERATOR;
        }
        JsonObject d = event("stream");
        d.addProperty("event", type.name().toLowerCase(Locale.ROOT).replace("stream_", ""));
        log.append(Kind.EVENT, src, d);

        if (type == StreamEventType.STREAM_STARTED) {
            streamFile = fingerprint(currentFile());
            if (streamFile != null) {
                JsonObject f = fileData("stream_start", streamFile);
                try {
                    f.addProperty("copied_to", log.archiveFile(streamFile));
                } catch (Exception ex) {
                    LOGGER.log(Level.WARNING, "Could not copy streamed file", ex);
                    f.add("copied_to", JsonNull.INSTANCE);
                }
                File processed = backend == null ? null : backend.getProcessedGcodeFile();
                if (processed != null) {
                    f.addProperty("processed_path", processed.getAbsolutePath());
                }
                log.append(Kind.FILE, src, f);
            }
        } else if (type == StreamEventType.STREAM_COMPLETE || type == StreamEventType.STREAM_CANCELED) {
            FileFingerprint fp = streamFile != null ? streamFile : fingerprint(currentFile());
            if (fp != null) {
                JsonObject f = fileData("stream_end", fp);
                f.addProperty("result", type == StreamEventType.STREAM_COMPLETE ? "complete" : "canceled");
                log.append(Kind.FILE, src, f);
            }
            streamFile = null;
        }
    }

    private void onFileState(FileStateEvent e) {
        FileState fs = e.getFileState();
        JsonObject d = event("file_state");
        d.addProperty("state", RecordMapper.lower(fs));
        File file = currentFile();
        if (file != null) {
            d.addProperty("path", file.getAbsolutePath());
        }
        String src = fs == FileState.FILE_STREAM_COMPLETE ? Src.UGS : Src.OPERATOR;
        log.append(Kind.EVENT, src, d);
        if (fs == FileState.FILE_LOADED) {
            FileFingerprint fp = fingerprint(file);
            if (fp != null) {
                log.append(Kind.FILE, Src.OPERATOR, fileData("loaded", fp));
            }
        }
    }

    private void onInterceptor(InterceptorStateEvent e) {
        interceptorState = e.getState();
        JsonObject d = event("interceptor");
        d.addProperty("state", RecordMapper.lower(e.getState()));
        d.addProperty("previous", RecordMapper.lower(e.getPreviousState()));
        e.getInterceptor().ifPresent(i -> d.addProperty("interceptor", i.getClass().getSimpleName()));
        e.getTriggerCommand().ifPresent(c -> {
            d.addProperty("trigger", c.getCommandString());
            if (!c.isGenerated() && c.getCommandNumber() >= 0) {
                d.addProperty("file_line", c.getCommandNumber() + 1);
            }
        });
        e.getPrompt().ifPresent(p -> {
            d.addProperty("step", p.stepId());
            JsonArray opts = new JsonArray();
            p.options().forEach(o -> opts.add(RecordMapper.lower(o)));
            d.add("options", opts);
        });
        log.append(Kind.EVENT, Src.UGS, d);
    }

    // ---------------------------------------------------------------- helpers

    private Attribution attribute(GcodeCommand cmd) {
        var origin = origins.lookup(cmd);
        if (origin.isPresent()) {
            return new Attribution(origin.get().cmdId(), origin.get().src(), origin.get().tag());
        }
        return new Attribution("u-" + cmd.getId(), defaultSrc(cmd), null);
    }

    private String defaultSrc(GcodeCommand cmd) {
        if (!cmd.isGenerated()) {
            return Src.UGS; // a line of the streamed file
        }
        if (interceptorState != InterceptorState.INACTIVE) {
            return Src.UGS;
        }
        if (controllerState == ControllerState.CONNECTING) {
            return Src.UGS; // controller initialization ($I, $$, $G, ...)
        }
        if (cmd.getClass().getSimpleName().startsWith("Get")) {
            return Src.UGS; // GetSettingsCommand, GetParserStateCommand, GetBuildInfoCommand, ...
        }
        return Src.OPERATOR;
    }

    private static JsonObject commandData(GcodeCommand cmd, Attribution a, String state) {
        JsonObject d = new JsonObject();
        d.addProperty("id", a.cmdId);
        d.addProperty("origin", Src.CONTROLLER.equals(a.src) ? Src.UGS : a.src);
        d.addProperty("line", cmd.getCommandString());
        d.addProperty("state", state);
        if (a.tag != null) {
            d.addProperty("tag", a.tag);
        }
        return d;
    }

    private static JsonArray responseLines(GcodeCommand cmd) {
        JsonArray arr = new JsonArray();
        if (cmd.getResponse() != null) {
            for (String l : cmd.getResponse().split("\n")) {
                String s = l.strip();
                if (!s.isEmpty() && !GrblStatusParser.isStatusReport(s)) {
                    arr.add(s);
                }
            }
        }
        return arr;
    }

    private static JsonObject event(String type) {
        JsonObject d = new JsonObject();
        d.addProperty("type", type);
        return d;
    }

    private static JsonObject fileData(String action, FileFingerprint fp) {
        JsonObject d = new JsonObject();
        d.addProperty("action", action);
        d.addProperty("path", fp.path().toString());
        d.addProperty("sha256", fp.sha256());
        d.addProperty("lines", fp.lines());
        return d;
    }

    private File currentFile() {
        return backend == null ? null : backend.getGcodeFile();
    }

    private static FileFingerprint fingerprint(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            return FileFingerprint.of(file.toPath());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not hash " + file, e);
            return null;
        }
    }

    private record Attribution(String cmdId, String src, String tag) {
    }

    private static boolean isStatusQuery(GcodeCommand cmd) {
        return "?".equals(cmd.getCommandString());
    }

    /** UGS's console echo of a sent command ({@code >>> G0 X1}, optionally origin-tagged) or of its {@code ok}. */
    static boolean isConsoleEcho(String text) {
        String t = text.strip();
        return CONSOLE_ECHO.matcher(t).find() || t.equalsIgnoreCase("ok");
    }

    private record InFlight(String cmdId, String src) {
    }

    private record ExpectedRealtime(byte b, String src, String cmdId, long deadline) {
    }
}
