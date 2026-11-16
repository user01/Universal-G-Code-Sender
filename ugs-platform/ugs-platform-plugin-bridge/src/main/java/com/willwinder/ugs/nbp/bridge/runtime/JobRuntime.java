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
package com.willwinder.ugs.nbp.bridge.runtime;

import com.google.gson.JsonObject;
import com.willwinder.ugs.nbp.bridge.job.BitSetter;
import com.willwinder.ugs.nbp.bridge.job.JobModel;
import com.willwinder.ugs.nbp.bridge.job.JobState;
import com.willwinder.ugs.nbp.bridge.job.Json;
import com.willwinder.ugs.nbp.bridge.job.MachineProfile;
import com.willwinder.ugs.nbp.bridge.job.OriginStore;
import com.willwinder.ugs.nbp.bridge.job.PlannerModel;
import com.willwinder.ugs.nbp.bridge.job.ResumeContext;
import com.willwinder.ugs.nbp.bridge.job.ResumePlan;
import com.willwinder.ugs.nbp.bridge.job.ResumePlanner;
import com.willwinder.ugs.nbp.bridge.job.ResumeTarget;
import com.willwinder.ugs.nbp.bridge.log.LogRecord;
import com.willwinder.ugs.nbp.bridge.log.SessionLog;
import com.willwinder.universalgcodesender.IController;
import com.willwinder.universalgcodesender.communicator.ICommunicator;
import com.willwinder.universalgcodesender.communicator.ICommunicatorListener;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.ControllerStatus;
import com.willwinder.universalgcodesender.listeners.UGSEventListener;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UGSEvent;
import com.willwinder.universalgcodesender.model.UnitUtils;
import com.willwinder.universalgcodesender.model.events.AlarmEvent;
import com.willwinder.universalgcodesender.model.events.CommandEvent;
import com.willwinder.universalgcodesender.model.events.CommandEventType;
import com.willwinder.universalgcodesender.model.events.ControllerStateEvent;
import com.willwinder.universalgcodesender.model.events.ControllerStatusEvent;
import com.willwinder.universalgcodesender.model.events.FileState;
import com.willwinder.universalgcodesender.model.events.FileStateEvent;
import com.willwinder.universalgcodesender.model.events.ProbeEvent;
import com.willwinder.universalgcodesender.model.events.StreamEvent;
import com.willwinder.universalgcodesender.services.interceptor.CommandInterceptorService;
import com.willwinder.universalgcodesender.types.GcodeCommand;
import com.willwinder.universalgcodesender.utils.Settings;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The bridge's job runtime (work packages J2/J3): origins capture, the job journal and the BitSetter tool-change
 * interceptor, wired to UGS events. One instance per application ({@link #current()}).
 *
 * <p>Read APIs for the panel and the HTTP layer: {@link #status()}, {@link #origins()}, {@link #originCheck()},
 * {@link #journal()}, {@link #plan(ResumeTarget)}; actions: {@link #applyOrigin(String)},
 * {@link #nameOrigin}, {@link #pinOrigin}, {@link #measureReference(boolean)}, {@link #setToolChangesEnabled}.
 * Results are plain objects that serialize with {@link Json#GSON} (snake_case, schema field names).
 *
 * <p>Threads: UGS events are queued to one runtime thread, which owns the origin tracker and the journal. Calls
 * from other threads that read them go through that thread ({@link #call}). Machine actions run on a separate
 * worker thread and only send through UGS.
 */
public final class JobRuntime implements UGSEventListener, ICommunicatorListener, BitSetterInterceptor.Host {
    private static final Logger LOGGER = Logger.getLogger(JobRuntime.class.getName());
    public static final String ORIGINS_FILE = "origins.json";
    public static final String PROFILE_FILE = "profile.json";
    static final String PROFILE_RESOURCE = "/com/willwinder/ugs/nbp/bridge/profiles/shapeoko4-standard.json";
    public static final double ORIGIN_TOLERANCE = 0.01;
    private static final Pattern G54_LINE = Pattern.compile("\\[G54:([-0-9.]+),([-0-9.]+),([-0-9.]+)[^]]*]");
    private static final Pattern PRB_OK = Pattern.compile("\\[PRB:[^]]*:1]");

    private static volatile JobRuntime current;

    /** Session folder of the session log (journals live there). */
    public interface SessionInfo {
        String session();

        Path sessionDir();

        static SessionInfo of(SessionLog log) {
            return new SessionInfo() {
                @Override
                public String session() {
                    return log.session();
                }

                @Override
                public Path sessionDir() {
                    return log.sessionDir();
                }
            };
        }
    }

    /** Snapshot for the panel and {@code GET /job/state}. */
    public record Status(boolean connected, boolean homed, String controllerState, double[] wco, double[] mpos,
                         String tool, Double bitsetterRef, String bitsetterRefTool, boolean toolChangesEnabled,
                         String jobFile, String jobSha256, String journalStream, Integer lineAcked, Integer lineDone,
                         OriginTracker.Check originCheck, ToolChangeOutcome lastToolChange, String profile) {
    }

    /** The last tool change: outcome {@code changed}, {@code aborted}, {@code failed}, {@code interrupted}. */
    public record ToolChangeOutcome(String outcome, String time, String tool, Double triggerMz, Double wcoZBefore,
                                    Double wcoZ, Double ref, double[] returnXy, String detail, List<String> sent) {
    }

    /** Result of {@link #applyOrigin}. */
    public record ApplyResult(boolean ok, String originId, double[] requested, double[] read, Double maxError,
                              List<String> sent, List<String> warnings, String detail) {
    }

    private final BackendAPI backend;
    private final Path home;
    private final Records records;
    private final SessionInfo sessionInfo;
    private final Clock clock;
    private final MachineProfile profile;
    private final OriginTracker origins;
    private final JournalTracker journal;
    private final BitSetterInterceptor interceptor;
    private final Path statePath;
    private final RuntimeState state;
    private final ExecutorService events = Executors.newSingleThreadExecutor(r -> daemon(r, "ugs-bridge-runtime"));
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> daemon(r, "ugs-bridge-runtime-io"));
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "ugs-bridge-journal"));

    // owned by the runtime thread
    private boolean connected;
    private boolean homed;
    private boolean homingSeen;
    private boolean toolMeasuredThisConnection;
    private ControllerState controllerState = ControllerState.DISCONNECTED;
    private double[] mpos;
    private ICommunicator communicator;
    private OriginTracker.Check connectCheck;
    private boolean connectCheckDone;
    private JobModel loadedJob;
    private Path loadedPath;
    private JobState lastJournal;
    private volatile ToolChangeOutcome lastToolChange;
    private volatile boolean attached;

    public JobRuntime(BackendAPI backend, Path home, Records records, SessionInfo sessionInfo, Clock clock,
                      MachineProfile profile) {
        this.backend = backend;
        this.home = home;
        this.records = records;
        this.sessionInfo = sessionInfo;
        this.clock = clock;
        this.profile = profile;
        this.statePath = home.resolve(RuntimeState.FILE);
        this.state = RuntimeState.read(statePath);
        this.origins = new OriginTracker(home.resolve(ORIGINS_FILE), records, clock, this::originContext);
        this.journal = new JournalTracker(records, clock, 1000, PlannerModel.GRBL);
        this.interceptor = new BitSetterInterceptor(this);
    }

    /** Starts the application's runtime (BridgeService). */
    public static synchronized JobRuntime start(BackendAPI backend, SessionLog log) {
        if (current != null) {
            return current;
        }
        JobRuntime rt = new JobRuntime(backend, log.home(), Records.of(log), SessionInfo.of(log),
                Clock.systemDefaultZone(), loadProfile(log.home()));
        rt.attach();
        current = rt;
        return rt;
    }

    public static synchronized void stopCurrent() {
        JobRuntime rt = current;
        current = null;
        if (rt != null) {
            rt.detach();
        }
    }

    /** The application's runtime, or null before the bridge started. */
    public static JobRuntime current() {
        return current;
    }

    /** {@code <home>/profile.json} if present, else the bundled Shapeoko 4 profile. */
    public static MachineProfile loadProfile(Path home) {
        Path p = home == null ? null : home.resolve(PROFILE_FILE);
        try {
            if (p != null && Files.exists(p)) {
                return MachineProfile.read(p);
            }
            try (InputStream in = JobRuntime.class.getResourceAsStream(PROFILE_RESOURCE)) {
                if (in != null) {
                    return MachineProfile.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not read the machine profile", e);
        }
        return new MachineProfile();
    }

    // ---------------------------------------------------------------- lifecycle

    public void attach() {
        if (attached) {
            return;
        }
        attached = true;
        if (state.activeJournal != null) {
            lastJournal = JournalTracker.recover(Path.of(state.activeJournal), records, clock);
        }
        backend.addUGSEventListener(this);
        CommandInterceptorService service = backend.getInterceptorService();
        if (service != null) {
            service.register(interceptor);
        }
        applyToolChangeOption();
        ticker.scheduleAtFixedRate(() -> events.execute(journal::tick), 1, 1, TimeUnit.SECONDS);
        if (backend.isConnected()) {
            events.execute(this::onConnect);
        }
    }

    public void detach() {
        if (!attached) {
            return;
        }
        attached = false;
        backend.removeUGSEventListener(this);
        CommandInterceptorService service = backend.getInterceptorService();
        if (service != null) {
            service.unregister(interceptor);
        }
        restoreStockToolChanges();
        ticker.shutdownNow();
        try {
            events.submit(() -> {
                journal.tick();
                detachCommunicator();
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "runtime shutdown", e);
        }
        events.shutdown();
        worker.shutdownNow();
    }

    /** Waits until queued events are processed (tests, and before answering reads). */
    public void awaitEvents() {
        try {
            call(() -> null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Runs {@code task} on the runtime thread and waits for it (10 s). */
    public <T> T call(Callable<T> task) throws Exception {
        Future<T> f = events.submit(task);
        return f.get(10, TimeUnit.SECONDS);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    // ---------------------------------------------------------------- UGS events

    @Override
    public void UGSEvent(UGSEvent evt) {
        if (!attached) {
            return;
        }
        try {
            events.execute(() -> {
                try {
                    handle(evt);
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "runtime: could not handle " + evt.getClass().getSimpleName(), e);
                }
            });
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "runtime stopped", e);
        }
    }

    private void handle(UGSEvent evt) {
        if (evt instanceof ControllerStateEvent e) {
            onControllerState(e.getState());
        } else if (evt instanceof ControllerStatusEvent e) {
            onStatus(e.getStatus());
        } else if (evt instanceof CommandEvent e) {
            onCommand(e.getCommandEventType(), e.getCommand());
        } else if (evt instanceof StreamEvent e) {
            switch (e.getType()) {
                case STREAM_STARTED -> startJournal();
                case STREAM_PAUSED -> journal.onPaused();
                case STREAM_RESUMED -> journal.onResumed();
                case STREAM_CANCELED -> journal.onStopped("canceled");
                case STREAM_COMPLETE -> journal.onCompleted();
            }
        } else if (evt instanceof FileStateEvent e && e.getFileState() == FileState.FILE_LOADED) {
            loadJob(currentFile());
        } else if (evt instanceof ProbeEvent) {
            origins.onProbe();
        } else if (evt instanceof AlarmEvent e) {
            journal.onAlarm(e.getAlarm() == null ? null : e.getAlarm().toString());
        }
    }

    private void onControllerState(ControllerState s) {
        ControllerState prev = controllerState;
        controllerState = s;
        if (s == ControllerState.DISCONNECTED) {
            if (connected) {
                connected = false;
                journal.onStopped("disconnected");
                detachCommunicator();
            }
            return;
        }
        if (!connected) {
            onConnect();
        }
        if (s == ControllerState.HOME) {
            homingSeen = true;
        } else if (s == ControllerState.IDLE && (prev == ControllerState.HOME || homingSeen)) {
            homed = true;
            homingSeen = false;
        } else if (s == ControllerState.ALARM) {
            homingSeen = false;
            if (journal.running()) {
                journal.onAlarm("controller alarm");
            }
        }
    }

    private void onConnect() {
        connected = true;
        homed = false;
        homingSeen = false;
        toolMeasuredThisConnection = false;
        connectCheck = null;
        connectCheckDone = false;
        origins.onConnect();
        attachCommunicator();
    }

    private void onStatus(ControllerStatus st) {
        if (st == null) {
            return;
        }
        if (!connected && st.getState() != null && st.getState() != ControllerState.DISCONNECTED) {
            onConnect();
        }
        double[] m = mm(st.getMachineCoord());
        if (m != null) {
            mpos = m;
        }
        double[] wco = mm(st.getWorkCoordinateOffset());
        if (wco != null && connected) {
            origins.observe(wco, "status");
            if (!connectCheckDone) {
                connectCheckDone = true;
                connectCheck = origins.check(loadedSha(), ORIGIN_TOLERANCE);
                if (connectCheck != null && !connectCheck.matches()) {
                    LOGGER.warning("ugs-bridge: " + connectCheck.detail());
                }
            }
        }
        boolean idle = st.getState() == ControllerState.IDLE;
        boolean drained = idle && !awaitingResponses();
        journal.onStatus(m, wco, drained);
    }

    private boolean awaitingResponses() {
        try {
            IController c = backend.getController();
            return c != null && c.getCommunicator() != null && c.getCommunicator().hasCommandsAwaitingResponse();
        } catch (RuntimeException e) {
            return true;
        }
    }

    private void onCommand(CommandEventType type, GcodeCommand cmd) {
        if (cmd == null) {
            return;
        }
        String line = cmd.getCommandString();
        if (type == CommandEventType.COMMAND_SENT) {
            origins.onCommandSent(line);
            return;
        }
        boolean fileLine = !cmd.isGenerated() && cmd.getCommandNumber() >= 0;
        if (fileLine && (type == CommandEventType.COMMAND_SKIPPED || cmd.isOk())) {
            journal.onAck(cmd.getCommandNumber());
        }
        if (type == CommandEventType.COMMAND_COMPLETE && cmd.isOk() && line != null
                && line.trim().equalsIgnoreCase("$H")) {
            homed = true;
        }
    }

    private void attachCommunicator() {
        detachCommunicator();
        try {
            IController c = backend.getController();
            communicator = c == null ? null : c.getCommunicator();
            if (communicator != null) {
                communicator.addListener(this);
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "runtime: no communicator", e);
        }
    }

    private void detachCommunicator() {
        if (communicator != null) {
            try {
                communicator.removeListener(this);
            } catch (RuntimeException ignored) {
                // closing
            }
        }
        communicator = null;
    }

    // communicator (wire) callbacks: forward to the runtime thread

    @Override
    public void rawResponseListener(String response) {
        if (response == null) {
            return;
        }
        Matcher m = G54_LINE.matcher(response);
        if (m.find()) {
            double[] g54 = {Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3))};
            double k = reportsInches() ? 25.4 : 1;
            double[] mmV = {g54[0] * k, g54[1] * k, g54[2] * k};
            events.execute(() -> origins.observe(mmV, "$#"));
        } else if (PRB_OK.matcher(response).find()) {
            events.execute(origins::onProbe);
        }
    }

    private boolean reportsInches() {
        try {
            IController c = backend.getController();
            return c != null && c.getFirmwareSettings().getReportingUnits() == UnitUtils.Units.INCH;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void commandSent(GcodeCommand command) {
        // G10 lines are seen through CommandEvent.COMMAND_SENT
    }

    @Override
    public void commandSkipped(GcodeCommand command) {
        // via CommandEvent
    }

    @Override
    public void communicatorPausedOnError() {
        // the stream event follows
    }

    @Override
    public void onConnectionClosed() {
        // the controller state event follows
    }

    // ---------------------------------------------------------------- job + journal

    private File currentFile() {
        try {
            return backend.getGcodeFile();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private JobModel loadJob(File file) {
        if (file == null) {
            return null;
        }
        Path p = file.toPath().toAbsolutePath();
        if (loadedJob != null && p.equals(loadedPath)) {
            return loadedJob;
        }
        try {
            loadedJob = JobModel.load(p);
            loadedPath = p;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not analyze " + p, e);
            loadedJob = null;
            loadedPath = null;
        }
        return loadedJob;
    }

    private String loadedSha() {
        JobModel j = loadedJob;
        return j == null ? null : j.sha256();
    }

    private void startJournal() {
        JobModel job = loadJob(currentFile());
        if (job == null) {
            LOGGER.warning("ugs-bridge: stream started without a readable file; no job journal");
            return;
        }
        // reload if the file changed on disk since it was loaded
        try {
            if (!Json.sha256Hex(Files.readAllBytes(loadedPath)).equals(job.sha256())) {
                loadedJob = null;
                job = loadJob(loadedPath.toFile());
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "sha check", e);
        }
        if (job == null) {
            return;
        }
        journal.start(job, sessionInfo.sessionDir(), sessionInfo.session(), origins.currentWco(), mpos,
                state.currentTool, reference());
        Optional<OriginStore.Origin> o = origins.pinnedFor(job.sha256()).or(origins::latest);
        o.ifPresent(origin -> journal.setOriginId(origin.id));
        state.activeJournal = journal.path() == null ? null : journal.path().toString();
        saveState();
    }

    // ---------------------------------------------------------------- BitSetterInterceptor.Host

    @Override
    public boolean toolChangesEnabled() {
        return state.bitsetterToolChanges;
    }

    @Override
    public MachineProfile profile() {
        return profile;
    }

    @Override
    public JobModel job() {
        JobModel j = journal.job();
        return j != null && journal.running() ? j : loadedJob;
    }

    /** The stored BitSetter reference, or null when there is none or the profile's BitSetter XY changed (D9). */
    @Override
    public Double reference() {
        RuntimeState.Reference r = state.reference;
        if (r == null) {
            return null;
        }
        List<String> why = BitSetter.invalidationReasons(
                new BitSetter.Reference(r.ref, r.triggerMz, r.tool, r.bitsetterXy, null), profile, null, false);
        return why.isEmpty() ? r.ref : null;
    }

    @Override
    public ToolChangeRoutine.Events events() {
        return new ToolChangeRoutine.Events() {
            @Override
            public void tool(JsonObject data) {
                records.append(LogRecord.Kind.TOOL, LogRecord.Src.BRIDGE, data);
            }

            @Override
            public void expectOrigin(String method, String tool, String note) {
                events.execute(() -> origins.expect(method, tool, note, null));
            }
        };
    }

    @Override
    public void toolChanged(ToolChangeRoutine.Result r, List<String> sent) {
        lastToolChange = new ToolChangeOutcome("changed", Instant.ofEpochMilli(clock.millis()).toString(), r.tool(),
                r.triggerZ(), r.wcoZBefore(), r.wcoZ(), r.ref(), r.returnXy(), r.label(), sent);
        events.execute(() -> {
            state.currentTool = r.tool();
            toolMeasuredThisConnection = true;
            saveState();
            journal.setTool(r.tool());
        });
    }

    @Override
    public void toolChangeEnded(String outcome, String detail, List<String> sent) {
        lastToolChange = new ToolChangeOutcome(outcome, Instant.ofEpochMilli(clock.millis()).toString(), null, null,
                null, null, reference(), null, detail, sent);
    }

    // ---------------------------------------------------------------- tool-change option

    /** The bridge's "BitSetter tool changes" option; while on, UGS's own tool-change handling is off. */
    public synchronized void setToolChangesEnabled(boolean on) {
        state.bitsetterToolChanges = on;
        applyToolChangeOption();
        saveState();
    }

    private synchronized void applyToolChangeOption() {
        Settings s = settings();
        if (s == null) {
            return;
        }
        if (state.bitsetterToolChanges) {
            if (s.isHandleToolChanges()) {
                state.savedHandleToolChanges = true;
                s.setHandleToolChanges(false);
            } else if (state.savedHandleToolChanges == null) {
                state.savedHandleToolChanges = false;
            }
        } else {
            restoreStockToolChanges();
        }
        saveState();
    }

    private synchronized void restoreStockToolChanges() {
        Settings s = settings();
        if (s != null && state.savedHandleToolChanges != null) {
            if (state.savedHandleToolChanges) {
                s.setHandleToolChanges(true);
            }
            state.savedHandleToolChanges = null;
            saveState();
        }
    }

    private Settings settings() {
        try {
            return backend.getSettings();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private synchronized void saveState() {
        try {
            state.write(statePath);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not write " + statePath, e);
        }
    }

    // ---------------------------------------------------------------- read APIs

    public Status status() {
        try {
            return call(() -> {
                JobState j = journal.snapshot();
                JobState shown = j != null ? j : lastJournal;
                RuntimeState.Reference r = state.reference;
                return new Status(connected, homed, controllerState == null ? null : controllerState.name().toLowerCase(Locale.ROOT),
                        origins.currentWco(), mpos == null ? null : BitSetter.round3(mpos), state.currentTool,
                        reference(), r == null ? null : r.tool, state.bitsetterToolChanges,
                        loadedPath == null ? null : loadedPath.toString(), loadedSha(),
                        shown == null ? null : shown.stream, shown == null ? null : shown.lineAcked,
                        shown == null ? null : shown.lineDone, origins.check(loadedSha(), ORIGIN_TOLERANCE),
                        lastToolChange, profile.name);
            });
        } catch (Exception e) {
            throw new IllegalStateException("runtime not responding", e);
        }
    }

    /** The live journal, else the last one (possibly {@code crashed}), else null. */
    public JobState journal() {
        try {
            return call(() -> {
                JobState j = journal.snapshot();
                if (j != null) {
                    return j;
                }
                if (lastJournal != null) {
                    return lastJournal.copy();
                }
                return null;
            });
        } catch (Exception e) {
            throw new IllegalStateException("runtime not responding", e);
        }
    }

    public List<OriginStore.Origin> origins() {
        try {
            return call(origins::list);
        } catch (Exception e) {
            throw new IllegalStateException("runtime not responding", e);
        }
    }

    /** Current G54 vs the origin pinned to the loaded file; null when nothing is pinned. */
    public OriginTracker.Check originCheck() {
        try {
            return call(() -> origins.check(loadedSha(), ORIGIN_TOLERANCE));
        } catch (Exception e) {
            throw new IllegalStateException("runtime not responding", e);
        }
    }

    /** The comparison made when the first G54 arrived after connecting; null when nothing was pinned. */
    public OriginTracker.Check connectCheck() {
        try {
            return call(() -> connectCheck);
        } catch (Exception e) {
            throw new IllegalStateException("runtime not responding", e);
        }
    }

    public OriginStore.Origin nameOrigin(String id, String name) throws Exception {
        return call(() -> origins.name(id, name));
    }

    /** Pins an origin to a job file; {@code jobSha256} null = the loaded file. */
    public OriginStore.Origin pinOrigin(String id, String jobSha256) throws Exception {
        return call(() -> {
            String sha = jobSha256 != null ? jobSha256 : loadedSha();
            if (sha == null) {
                throw new IllegalStateException("no file loaded to pin to");
            }
            return origins.pin(id, sha);
        });
    }

    /**
     * Resume plan for the loaded file (SPEC §11.6) with the runtime's context: homed in this connection, current
     * G54, tool in the collet, BitSetter reference, the journal for this file.
     *
     * @param adjust optional changes to the context (e.g. {@code midContour}, {@code powerLoss})
     */
    public ResumePlan plan(ResumeTarget target, Consumer<ResumeContext> adjust) throws Exception {
        return call(() -> {
            JobModel job = loadJob(currentFile());
            if (job == null) {
                throw new IllegalStateException("no file loaded");
            }
            JobState j = journal.snapshot();
            if (j == null || j.file == null || !job.sha256().equals(j.file.sha256)) {
                j = lastJournal != null && lastJournal.file != null && job.sha256().equals(lastJournal.file.sha256)
                        ? lastJournal : null;
            }
            ResumeContext ctx = new ResumeContext();
            ctx.homed = homed;
            ctx.currentWco = origins.currentWco();
            ctx.currentTool = state.currentTool;
            ctx.bitsetterRef = reference();
            ctx.toolLengthKnown = toolMeasuredThisConnection;
            ctx.powerLoss = j != null && "crashed".equals(j.stream);
            if (adjust != null) {
                adjust.accept(ctx);
            }
            ResumePlan p = ResumePlanner.plan(job, profile, j, ctx, target);
            JsonObject d = new JsonObject();
            d.addProperty("event", "resume_planned");
            d.addProperty("file_sha256", job.sha256());
            d.addProperty("line", p.resumeLine);
            d.addProperty("op", p.operation);
            d.addProperty("plan_id", p.planId);
            d.addProperty("ready", p.ready);
            records.append(LogRecord.Kind.JOB, LogRecord.Src.BRIDGE, d);
            return p;
        });
    }

    public ResumePlan plan(ResumeTarget target) throws Exception {
        return plan(target, null);
    }

    // ---------------------------------------------------------------- machine actions

    /**
     * Apply origin (SPEC §11.3): {@code G10 L2 P1 X Y Z} with the stored G54, then {@code $#} must read it back
     * within 0.01 mm. Needs a connected, idle machine that isn't streaming. Recorded as method {@code restored}.
     */
    public CompletableFuture<ApplyResult> applyOrigin(String id) {
        CompletableFuture<ApplyResult> out = new CompletableFuture<>();
        worker.execute(() -> {
            try {
                out.complete(doApplyOrigin(id));
            } catch (Exception e) {
                out.completeExceptionally(e);
            }
        });
        return out;
    }

    private ApplyResult doApplyOrigin(String id) throws Exception {
        OriginStore.Origin o = call(() -> origins.find(id).orElse(null));
        if (o == null) {
            throw new IllegalArgumentException("no origin " + id);
        }
        IController c = requireIdleController();
        List<String> warnings = new ArrayList<>();
        boolean isHomed = call(() -> homed);
        if (!isHomed) {
            warnings.add("not homed in this connection: the machine coordinates (and so this origin) may be off");
        }
        String tool = call(() -> state.currentTool);
        if (o.tool != null && tool != null && !o.tool.equals(tool)) {
            warnings.add("the origin was taken with " + o.tool + " but " + tool + " is in the collet: Z belongs to "
                    + o.tool + " (measure the tool on the BitSetter to correct Z)");
        }
        double[] w = BitSetter.round3(o.wco);
        String cmd = String.format(Locale.ROOT, "G10 L2 P%d X%.3f Y%.3f Z%.3f", BitSetter.wcsIndex(o.wcs),
                w[0], w[1], w[2]);
        List<String> sent = new ArrayList<>();
        events.execute(() -> origins.expect("restored", o.tool, "apply origin " + o.id
                + (o.name != null ? " '" + o.name + "'" : ""), o.id));
        try (UgsMachineIo io = new UgsMachineIo(c, sent::add)) {
            io.send(cmd);
            double[] read = BitSetter.round3(io.readG54());
            double err = 0;
            for (int i = 0; i < 3; i++) {
                err = Math.max(err, Math.abs(read[i] - w[i]));
            }
            err = BitSetter.round3(err);
            boolean ok = err <= ORIGIN_TOLERANCE;
            return new ApplyResult(ok, o.id, w, read, err, sent, warnings, ok
                    ? "G54 restored and verified with $#"
                    : String.format(Locale.ROOT, "G54 reads back %.3f mm off: check the controller", err));
        }
    }

    /**
     * First-tool BitSetter reference (SPEC §11.4) for the setup checklist: run after BitZero Z on the stock. Stores
     * {@code ref = WCO_z − S} and the tool in the collet.
     *
     * @param tool         tool in the collet (e.g. {@code T02}); null = the first tool of the loaded job
     * @param verifyRepeat measure twice and check {@code repeat_tolerance}
     */
    public CompletableFuture<ToolChangeRoutine.Reference> measureReference(String tool, boolean verifyRepeat) {
        CompletableFuture<ToolChangeRoutine.Reference> out = new CompletableFuture<>();
        worker.execute(() -> {
            try {
                IController c = requireIdleController();
                String t = tool != null ? tool : call(this::firstTool);
                ToolChangeRoutine.Reference r;
                try (UgsMachineIo io = new UgsMachineIo(c, null)) {
                    ToolChangeRoutine routine = new ToolChangeRoutine(profile, io, p -> {
                        throw new com.willwinder.universalgcodesender.services.interceptor.InterceptAbortedException(
                                "no operator prompt outside a tool change");
                    }, events());
                    r = routine.measureReference(t, verifyRepeat);
                }
                call(() -> {
                    RuntimeState.Reference ref = new RuntimeState.Reference();
                    ref.ref = r.ref();
                    ref.triggerMz = r.triggerZ();
                    ref.wcoZ = r.wcoZ();
                    ref.tool = r.tool();
                    ref.bitsetterXy = profile.bitsetter == null ? null : profile.bitsetter.xy;
                    ref.created = Instant.ofEpochMilli(clock.millis()).toString();
                    state.reference = ref;
                    state.currentTool = r.tool();
                    toolMeasuredThisConnection = true;
                    saveState();
                    journal.setReference(r.ref());
                    return null;
                });
                out.complete(r);
            } catch (Exception e) {
                out.completeExceptionally(e);
            }
        });
        return out;
    }

    public CompletableFuture<ToolChangeRoutine.Reference> measureReference(boolean verifyRepeat) {
        return measureReference(null, verifyRepeat);
    }

    private String firstTool() {
        JobModel j = loadedJob;
        if (j == null || j.toolChangeLines().isEmpty()) {
            return state.currentTool;
        }
        int t = j.toolChangeTarget(j.toolChangeLines().get(0));
        return t > 0 ? j.toolId(t) : state.currentTool;
    }

    private IController requireIdleController() {
        IController c = backend.getController();
        if (c == null || !backend.isConnected()) {
            throw new IllegalStateException("not connected");
        }
        if (Boolean.TRUE.equals(c.isStreaming()) || backend.isSendingFile()) {
            throw new IllegalStateException("a file is streaming");
        }
        ControllerStatus st = c.getControllerStatus();
        if (st == null || st.getState() != ControllerState.IDLE) {
            throw new IllegalStateException("the machine is not idle (" + (st == null ? "unknown" : st.getState()) + ")");
        }
        return c;
    }

    // ---------------------------------------------------------------- helpers

    private OriginTracker.Context originContext() {
        return new OriginTracker.Context(sessionInfo.session(), loadedSha(), state.currentTool, reference(), profile.name);
    }

    private static double[] mm(Position p) {
        if (p == null || Double.isNaN(p.getX())) {
            return null;
        }
        Position m = p.getPositionIn(UnitUtils.Units.MM);
        return new double[]{m.getX(), m.getY(), m.getZ()};
    }

    /** For tests: the persisted state. */
    RuntimeState state() {
        return state;
    }

    BitSetterInterceptor interceptor() {
        return interceptor;
    }
}
