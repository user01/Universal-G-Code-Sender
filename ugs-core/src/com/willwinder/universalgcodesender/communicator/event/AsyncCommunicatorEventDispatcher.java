/*
    Copyright 2012-2022 Will Winder

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
package com.willwinder.universalgcodesender.communicator.event;

import com.willwinder.universalgcodesender.communicator.ICommunicatorListener;
import com.willwinder.universalgcodesender.types.GcodeCommand;

import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A communicator event dispatcher that will dispatch events asynchronously
 * to all listeners in its own thread as soon as an event is queued.
 * <p>
 * If commands complete very fast, like several comments in a row being
 * skipped, then multiple event handlers could process them out of order. To
 * prevent that from happening we use a blocking queue to add events in the
 * main thread, and process them in order with a single event thread.
 * <p>
 * This will also increase send performance as the communicator does not need
 * to wait for the UI to be refreshed before continuing sending the next command.
 *
 * @author winder
 * @author Joacim Breiler
 */
public class AsyncCommunicatorEventDispatcher extends CommunicatorEventDispatcher implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(AsyncCommunicatorEventDispatcher.class.getSimpleName());

    private final LinkedBlockingDeque<AsyncCommunicatorEvent> eventQueue = new LinkedBlockingDeque<>();
    private final AtomicReference<Thread> eventThread = new AtomicReference<>();
    // ugs-bridge P4: reset() no longer throws queued events away; they become stale (older generation)
    // and are only delivered to listeners that ask for them (a session log must see every wire line)
    private final AtomicLong generation = new AtomicLong();

    private void start() {
        if (isStopped()) {
            Thread thread = Executors.defaultThreadFactory().newThread(this);
            thread.setName(AsyncCommunicatorEventDispatcher.class.getSimpleName());
            eventThread.set(thread);
            thread.start();
        }
    }

    @Override
    public void reset() {
        Thread thread = eventThread.get();
        generation.incrementAndGet();
        if (thread != null) {
            thread.interrupt();
        }
    }

    public int getEventCount() {
        return eventQueue.size();
    }

    @Override
    public void run() {
        boolean isRunning = true;
        while (isRunning) {
            try {
                AsyncCommunicatorEvent e = eventQueue.take();
                if (e.generation != generation.get()) {
                    dispatchDiscarded(e);
                } else if (e.event == AsyncCommunicatorEventType.REALTIME_BYTE_SENT) {
                    super.realtimeByteSent(e.realtimeByte);
                } else {
                    dispatchEvent(e.event, e.response, e.command);
                }
            } catch (InterruptedException ignored) {
                isRunning = false;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Could not send event", e);
                isRunning = false;
            }
        }
        eventThread.set(null);
    }

    public boolean isStopped() {
        return eventThread.get() == null;
    }

    @Override
    public void rawResponseListener(String response) {
        queue(AsyncCommunicatorEventType.RAW_RESPONSE, response, null, (byte) 0);
        start();
    }

    @Override
    public void commandSent(GcodeCommand command) {
        queue(AsyncCommunicatorEventType.COMMAND_SENT, null, command, (byte) 0);
        start();
    }

    @Override
    public void realtimeByteSent(byte b) {
        queue(AsyncCommunicatorEventType.REALTIME_BYTE_SENT, null, null, b);
        start();
    }

    @Override
    public void commandSkipped(GcodeCommand command) {
        queue(AsyncCommunicatorEventType.COMMAND_SKIPPED, null, command, (byte) 0);
        start();
    }

    @Override
    public void communicatorPausedOnError() {
        queue(AsyncCommunicatorEventType.PAUSED, null, null, (byte) 0);
        start();
    }

    private void queue(AsyncCommunicatorEventType type, String response, GcodeCommand command, byte b) {
        eventQueue.add(new AsyncCommunicatorEvent(type, response, command, b, generation.get()));
    }

    /** An event queued before the last reset: only for listeners that want discarded events. */
    private void dispatchDiscarded(AsyncCommunicatorEvent e) {
        communicatorListeners.stream().filter(ICommunicatorListener::receivesDiscardedEvents).forEach(l -> {
            switch (e.event) {
                case COMMAND_SENT -> l.commandSent(e.command);
                case COMMAND_SKIPPED -> l.commandSkipped(e.command);
                case RAW_RESPONSE -> l.rawResponseListener(e.response);
                case REALTIME_BYTE_SENT -> l.realtimeByteSent(e.realtimeByte);
                default -> {
                    // PAUSED is not replayed
                }
            }
        });
    }

    private void dispatchEvent(AsyncCommunicatorEventType event, String response, GcodeCommand command) {
        switch (event) {
            case COMMAND_SENT:
                super.commandSent(command);
                break;
            case COMMAND_SKIPPED:
                super.commandSkipped(command);
                break;
            case RAW_RESPONSE:
                super.rawResponseListener(response);
                break;
            case PAUSED:
                super.communicatorPausedOnError();
                break;
            default:
        }
    }
}
