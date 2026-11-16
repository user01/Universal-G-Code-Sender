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

import com.willwinder.ugs.nbp.bridge.job.JobModel;
import com.willwinder.ugs.nbp.bridge.job.JobState;
import com.willwinder.ugs.nbp.bridge.job.OriginStore;
import com.willwinder.ugs.nbp.bridge.job.ResumePlan;
import com.willwinder.ugs.nbp.bridge.job.ResumeTarget;
import com.willwinder.universalgcodesender.IController;
import com.willwinder.universalgcodesender.communicator.ICommunicator;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.ControllerStatus;
import com.willwinder.universalgcodesender.listeners.ControllerStatusBuilder;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils;
import com.willwinder.universalgcodesender.model.events.CommandEvent;
import com.willwinder.universalgcodesender.model.events.CommandEventType;
import com.willwinder.universalgcodesender.model.events.ControllerStateEvent;
import com.willwinder.universalgcodesender.model.events.ControllerStatusEvent;
import com.willwinder.universalgcodesender.model.events.FileState;
import com.willwinder.universalgcodesender.model.events.FileStateEvent;
import com.willwinder.universalgcodesender.model.events.StreamEvent;
import com.willwinder.universalgcodesender.model.events.StreamEventType;
import com.willwinder.universalgcodesender.services.interceptor.CommandInterceptorService;
import com.willwinder.universalgcodesender.types.GcodeCommand;
import com.willwinder.universalgcodesender.utils.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class JobRuntimeTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final OriginTrackerTest.MutableClock clock = new OriginTrackerTest.MutableClock();
    private final Fixtures.Recs recs = new Fixtures.Recs();
    private BackendAPI backend;
    private Settings settings;
    private CommandInterceptorService interceptors;
    private Path home;
    private Path session;
    private Path nc;
    private JobRuntime rt;

    @Before
    public void setUp() throws Exception {
        home = tmp.newFolder("home").toPath();
        session = tmp.newFolder("session").toPath();
        nc = tmp.newFile("two.nc").toPath();
        Files.writeString(nc, Fixtures.TWO_TOOLS);
        backend = mock(BackendAPI.class);
        settings = mock(Settings.class);
        interceptors = mock(CommandInterceptorService.class);
        IController controller = mock(IController.class);
        ICommunicator comm = mock(ICommunicator.class);
        when(backend.getSettings()).thenReturn(settings);
        when(backend.getInterceptorService()).thenReturn(interceptors);
        when(backend.getController()).thenReturn(controller);
        when(controller.getCommunicator()).thenReturn(comm);
        when(backend.getGcodeFile()).thenReturn(nc.toFile());
        rt = newRuntime();
    }

    private JobRuntime newRuntime() {
        JobRuntime r = new JobRuntime(backend, home, recs, new JobRuntime.SessionInfo() {
            @Override
            public String session() {
                return "20261115-100000-tcp";
            }

            @Override
            public Path sessionDir() {
                return session;
            }
        }, clock, Fixtures.profile());
        r.attach();
        return r;
    }

    @After
    public void tearDown() {
        rt.detach();
    }

    private static ControllerStatus status(ControllerState s, double[] mpos, double[] wco) {
        return ControllerStatusBuilder.newInstance().setState(s)
                .setMachineCoord(new Position(mpos[0], mpos[1], mpos[2], UnitUtils.Units.MM))
                .setWorkCoordinateOffset(wco == null ? null : new Position(wco[0], wco[1], wco[2], UnitUtils.Units.MM))
                .build();
    }

    private void statusEvent(ControllerState s, double[] mpos, double[] wco) {
        ControllerStatus st = status(s, mpos, wco);
        rt.UGSEvent(new ControllerStatusEvent(st, st));
    }

    private static GcodeCommand fileLine(JobModel job, int line) {
        GcodeCommand c = new GcodeCommand(job.line(line), job.line(line), "", line);
        c.setResponse("ok");
        c.setOk(true);
        return c;
    }

    @Test
    public void registersTheInterceptorAndSwapsUgsToolChangeHandling() {
        verify(interceptors).register(rt.interceptor());
        when(settings.isHandleToolChanges()).thenReturn(true);
        rt.setToolChangesEnabled(true);
        verify(settings).setHandleToolChanges(false);
        assertTrue(rt.toolChangesEnabled());
        assertEquals(Boolean.TRUE, RuntimeState.read(home.resolve(RuntimeState.FILE)).savedHandleToolChanges);

        rt.setToolChangesEnabled(false);
        verify(settings).setHandleToolChanges(true);
        assertNull(RuntimeState.read(home.resolve(RuntimeState.FILE)).savedHandleToolChanges);

        // the interceptor only matches while the option is on
        assertFalse(rt.interceptor().matches(new GcodeCommand("T21 M6", "T21 M6", "", 20)));
        rt.setToolChangesEnabled(true);
        assertTrue(rt.interceptor().matches(new GcodeCommand("T21M6", "T21 M6", "", 20)));
        assertFalse(rt.interceptor().matches(new GcodeCommand("M60", "M60", "", 3)));
        assertFalse(rt.interceptor().restoresPosition());
    }

    @Test
    public void stockSettingStaysOffWhenItWasOff() {
        when(settings.isHandleToolChanges()).thenReturn(false);
        rt.setToolChangesEnabled(true);
        rt.setToolChangesEnabled(false);
        verify(settings, never()).setHandleToolChanges(true);
    }

    @Test
    public void originsAndJournalFromUgsEvents() throws Exception {
        JobModel job = JobModel.load(nc);
        rt.UGSEvent(new FileStateEvent(FileState.FILE_LOADED));
        rt.UGSEvent(new ControllerStateEvent(ControllerState.ALARM, ControllerState.CONNECTING));
        statusEvent(ControllerState.ALARM, new double[]{0, 0, 0}, new double[]{-300, -350, -60});
        rt.UGSEvent(new ControllerStateEvent(ControllerState.HOME, ControllerState.ALARM));
        rt.UGSEvent(new ControllerStateEvent(ControllerState.IDLE, ControllerState.HOME));
        rt.UGSEvent(new CommandEvent(CommandEventType.COMMAND_SENT, new GcodeCommand("G10 L2 P1 Z-55.6")));
        statusEvent(ControllerState.IDLE, new double[]{-1, -1, -1}, new double[]{-300, -350, -55.6});
        rt.awaitEvents();

        List<OriginStore.Origin> origins = rt.origins();
        assertEquals(List.of("observed", "g10"), origins.stream().map(o -> o.method).toList());
        assertEquals(-55.6, origins.get(1).wco[2], 0);
        assertEquals(job.sha256(), origins.get(1).jobSha256);
        assertTrue(rt.status().homed());
        assertNull(rt.originCheck()); // nothing pinned yet
        rt.pinOrigin(origins.get(1).id, null);
        rt.nameOrigin(origins.get(1).id, "dr ryan stock");
        assertTrue(rt.originCheck().matches());

        rt.UGSEvent(new StreamEvent(StreamEventType.STREAM_STARTED));
        for (int line = 1; line <= 15; line++) {
            rt.UGSEvent(new CommandEvent(CommandEventType.COMMAND_COMPLETE, fileLine(job, line)));
        }
        statusEvent(ControllerState.RUN, new double[]{-290, -340, -57.6}, new double[]{-300, -350, -55.6});
        rt.awaitEvents();
        JobState s = rt.journal();
        assertEquals("running", s.stream);
        assertEquals(15, s.lineAcked);
        assertEquals("pocket", s.operation);
        assertEquals(origins.get(1).id, s.originId);
        assertEquals(-290.0, s.lastMpos[0], 0);
        Path journal = session.resolve(JobState.fileName(job.sha256()));
        assertTrue(Files.exists(journal));
        assertEquals(journal.toString(), rt.state().activeJournal);

        ResumePlan p = rt.plan(new ResumeTarget.Stopped());
        assertTrue(p.resumeLine >= 1 && p.resumeLine <= 15);
        assertEquals(job.sha256(), p.fileSha256);

        rt.UGSEvent(new StreamEvent(StreamEventType.STREAM_CANCELED));
        rt.awaitEvents();
        assertEquals("stopped", rt.journal().stream);
        List<String> jobEvents = recs.of("job").stream().map(d -> d.get("event").getAsString()).toList();
        assertEquals(List.of("started", "op_start", "resume_planned", "stopped"), jobEvents);
        assertEquals(2, recs.of("origin").size());
    }

    @Test
    public void runningJournalFromThePreviousLaunchIsCrashed() throws Exception {
        JobModel job = JobModel.load(nc);
        rt.UGSEvent(new ControllerStateEvent(ControllerState.IDLE, ControllerState.CONNECTING));
        rt.UGSEvent(new FileStateEvent(FileState.FILE_LOADED));
        rt.UGSEvent(new StreamEvent(StreamEventType.STREAM_STARTED));
        for (int line = 1; line <= 12; line++) {
            rt.UGSEvent(new CommandEvent(CommandEventType.COMMAND_COMPLETE, fileLine(job, line)));
        }
        rt.awaitEvents();
        clock.ms += 2000;
        rt.call(() -> null);
        // simulate a crash: no detach, the journal still says running; a new launch reads runtime.json
        Path journal = session.resolve(JobState.fileName(job.sha256()));
        assertEquals("running", JobState.read(journal).stream);
        JobRuntime next = newRuntime();
        try {
            assertEquals("crashed", JobState.read(journal).stream);
            assertEquals("crashed", next.journal().stream);
            assertEquals("crashed", next.status().journalStream());
        } finally {
            next.detach();
        }
    }
}
