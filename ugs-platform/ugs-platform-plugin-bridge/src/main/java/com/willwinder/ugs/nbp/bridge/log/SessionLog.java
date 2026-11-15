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
package com.willwinder.ugs.nbp.bridge.log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The JSONL session log (contract: bridge/docs/log-format.md).
 * <ul>
 *     <li>{@link #append} never blocks: it assigns seq/t/mono/session, keeps the record in an in-memory ring
 *     and hands it to a bounded queue drained by one writer thread.</li>
 *     <li>If the queue is full the record is dropped (no seq is consumed) and counted; as soon as there is
 *     room again the writer appends a {@code session} record {@code {event: "overflow", dropped: n}}.</li>
 *     <li>The writer writes each record with its own write call (no user-space buffering) and fsyncs at
 *     least once a second while there is unsynced data.</li>
 * </ul>
 */
public final class SessionLog implements AutoCloseable {
    public static final int DEFAULT_QUEUE_CAPACITY = 100_000;
    public static final int DEFAULT_RING_CAPACITY = 20_000;
    public static final String LOG_FILE = "session.jsonl";
    public static final String FILES_DIR = "files";

    private static final Logger LOGGER = Logger.getLogger(SessionLog.class.getName());
    private static final DateTimeFormatter T_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FOLDER_TIME_FORMAT = DateTimeFormatter.ofPattern("HHmmss");
    private static final DateTimeFormatter SESSION_PREFIX_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final long FSYNC_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final Pending POISON = new Pending(null, null, 0);

    private final Gson gson = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private final Path home;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final long startNanos;
    private final BlockingQueue<Pending> queue;
    private final int ringCapacity;
    private final ArrayDeque<LogRecord> ring;
    private final AtomicLong dropped = new AtomicLong();
    private final List<Consumer<LogRecord>> listeners = new CopyOnWriteArrayList<>();

    // guarded by this
    private long seq;
    private String session;
    private Path sessionDir;

    private volatile long writtenSeq;
    private volatile boolean closed;
    private Thread writer;

    public SessionLog(Path home) {
        this(home, Clock.systemDefaultZone(), System::nanoTime, DEFAULT_QUEUE_CAPACITY, DEFAULT_RING_CAPACITY);
    }

    SessionLog(Path home, Clock clock, LongSupplier nanoTime, int queueCapacity, int ringCapacity) {
        this.home = home;
        this.clock = clock;
        this.nanoTime = nanoTime;
        this.startNanos = nanoTime.getAsLong();
        this.queue = new ArrayBlockingQueue<>(queueCapacity + 1); // +1: room for the shutdown marker
        this.ringCapacity = ringCapacity;
        this.ring = new ArrayDeque<>(Math.min(ringCapacity, 1024));
    }

    public Path home() {
        return home;
    }

    public Path logsRoot() {
        return home.resolve("logs");
    }

    /** Starts the writer thread. */
    public synchronized void start() {
        if (writer != null) {
            return;
        }
        writer = new Thread(this::writerLoop, "ugs-bridge-log-writer");
        writer.setDaemon(true);
        writer.start();
    }

    /** Current session name, or null before the first connection. */
    public synchronized String session() {
        return session;
    }

    /** The folder records are currently written to; creates the {@code -noconn} folder if needed. */
    public synchronized Path sessionDir() {
        if (sessionDir == null) {
            sessionDir = createFolder("noconn", OffsetDateTime.now(clock));
        }
        return sessionDir;
    }

    /**
     * Starts a new session folder; all records appended afterwards belong to it.
     *
     * @param portSlug connection port with '/' replaced by '_' (see {@link #portSlug})
     * @return the session name ({@code YYYYMMDD-HHMMSS-<port-slug>})
     */
    public synchronized String openSession(String portSlug) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Path dir = createFolder(portSlug, now);
        sessionDir = dir;
        String suffix = dir.getFileName().toString().substring(FOLDER_TIME_FORMAT.format(now).length());
        session = SESSION_PREFIX_FORMAT.format(now) + suffix;
        return session;
    }

    private Path createFolder(String slug, OffsetDateTime now) {
        Path day = logsRoot().resolve(DAY_FORMAT.format(now));
        String base = FOLDER_TIME_FORMAT.format(now) + "-" + slug;
        Path dir = day.resolve(base);
        for (int i = 2; Files.exists(dir); i++) {
            dir = day.resolve(base + "-" + i);
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not create session folder " + dir, e);
        }
        return dir;
    }

    /**
     * Connection port to folder slug: {@code /dev/cu.usbmodem1101} gives {@code _dev_cu.usbmodem1101};
     * for TCP pass {@code tcp_<host>_<port>}.
     */
    public static String portSlug(String port) {
        if (port == null || port.isBlank()) {
            return "unknown";
        }
        return port.replace('/', '_').replace('\\', '_').replace(':', '_').replace(' ', '_');
    }

    /**
     * Appends a record. Never blocks. Returns the record, or null if it was dropped because the queue is full.
     */
    public LogRecord append(String kind, String src, JsonObject data) {
        LogRecord record;
        synchronized (this) {
            if (closed) {
                return null;
            }
            if (queue.remainingCapacity() <= 1) {
                dropped.incrementAndGet();
                return null;
            }
            Path dir = sessionDir();
            long s = ++seq;
            JsonObject envelope = new JsonObject();
            envelope.addProperty("seq", s);
            envelope.addProperty("t", T_FORMAT.format(OffsetDateTime.now(clock)));
            envelope.addProperty("mono", Math.round((nanoTime.getAsLong() - startNanos) / 1_000_000.0) / 1000.0);
            envelope.add("session", session == null ? JsonNull.INSTANCE : new JsonPrimitive(session));
            envelope.addProperty("kind", kind);
            envelope.addProperty("src", src);
            envelope.add("data", data == null ? new JsonObject() : data);
            record = new LogRecord(s, kind, src, session, gson.toJson(envelope));
            queue.add(new Pending(dir.resolve(LOG_FILE), record.json(), s));
            ring.addLast(record);
            while (ring.size() > ringCapacity) {
                ring.removeFirst();
            }
        }
        for (Consumer<LogRecord> l : listeners) {
            try {
                l.accept(record);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Log listener failed", e);
            }
        }
        return record;
    }

    /** Last assigned seq (0 if none). */
    public synchronized long lastSeq() {
        return seq;
    }

    /**
     * Records with seq greater than {@code since} still in the in-memory ring, oldest first.
     *
     * @param kinds kinds to include, or null/empty for all
     * @param limit maximum number of records (the oldest ones are returned first)
     */
    public synchronized List<LogRecord> since(long since, Collection<String> kinds, int limit) {
        List<LogRecord> out = new ArrayList<>();
        for (LogRecord r : ring) {
            if (out.size() >= limit) {
                break;
            }
            if (r.seq() > since && (kinds == null || kinds.isEmpty() || kinds.contains(r.kind()))) {
                out.add(r);
            }
        }
        return out;
    }

    /** Oldest seq still in the ring, or 0 if empty. Clients asking for older records must read the file. */
    public synchronized long oldestSeqInRing() {
        return ring.isEmpty() ? 0 : ring.peekFirst().seq();
    }

    /** Listeners are called synchronously from {@link #append}; they must not block. */
    public void addListener(Consumer<LogRecord> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<LogRecord> listener) {
        listeners.remove(listener);
    }

    /**
     * Copies a file into {@code files/<sha256>.nc} of the current session folder, once per session.
     *
     * @return the path relative to the session folder, e.g. {@code files/<sha256>.nc}
     */
    public String archiveFile(FileFingerprint fp) throws IOException {
        Path dir = sessionDir();
        String rel = FILES_DIR + "/" + fp.sha256() + ".nc";
        Path target = dir.resolve(rel);
        if (!Files.exists(target)) {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.copy(fp.path(), tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        return rel;
    }

    /** Number of records dropped and not yet reported in an overflow record. */
    public long pendingDropped() {
        return dropped.get();
    }

    /** Waits until every record appended so far is written (for tests and shutdown). */
    public boolean awaitWritten(long timeoutMillis) throws InterruptedException {
        long target = lastSeq();
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (writtenSeq < target || dropped.get() > 0) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            Thread.sleep(5);
            target = Math.max(target, lastSeq());
        }
        return true;
    }

    @Override
    public void close() {
        Thread w;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            w = writer;
        }
        if (w == null) {
            return;
        }
        queue.offer(POISON);
        try {
            w.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writerLoop() {
        Path currentFile = null;
        FileChannel channel = null;
        long lastSync = nanoTime.getAsLong();
        boolean dirty = false;
        try {
            while (true) {
                Pending p;
                try {
                    p = queue.poll(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
                if (p == POISON) {
                    break;
                }
                if (p != null) {
                    if (!p.file.equals(currentFile)) {
                        closeChannel(channel);
                        channel = open(p.file);
                        currentFile = p.file;
                    }
                    if (channel != null) {
                        try {
                            ByteBuffer buf = ByteBuffer.wrap((p.line + "\n").getBytes(StandardCharsets.UTF_8));
                            while (buf.hasRemaining()) {
                                channel.write(buf);
                            }
                            dirty = true;
                        } catch (IOException e) {
                            LOGGER.log(Level.WARNING, "Could not write session log record " + p.seq, e);
                        }
                    }
                    writtenSeq = p.seq;
                }
                long now = nanoTime.getAsLong();
                if (dirty && channel != null && now - lastSync >= FSYNC_INTERVAL_NANOS) {
                    force(channel);
                    dirty = false;
                    lastSync = now;
                }
                reportOverflow();
            }
            // drain whatever is left after the shutdown marker
            Pending p;
            while ((p = queue.poll()) != null) {
                if (p == POISON) {
                    continue;
                }
                if (!p.file.equals(currentFile)) {
                    closeChannel(channel);
                    channel = open(p.file);
                    currentFile = p.file;
                }
                if (channel != null) {
                    try {
                        channel.write(ByteBuffer.wrap((p.line + "\n").getBytes(StandardCharsets.UTF_8)));
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "Could not write session log record " + p.seq, e);
                    }
                }
                writtenSeq = p.seq;
            }
        } finally {
            closeChannel(channel);
        }
    }

    private void reportOverflow() {
        if (dropped.get() > 0 && queue.remainingCapacity() > 2) {
            long n = dropped.getAndSet(0);
            JsonObject data = new JsonObject();
            data.addProperty("event", "overflow");
            data.addProperty("dropped", n);
            if (append(LogRecord.Kind.SESSION, LogRecord.Src.BRIDGE, data) == null) {
                dropped.addAndGet(n);
            }
        }
    }

    private static FileChannel open(Path file) {
        try {
            Files.createDirectories(file.getParent());
            return FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not open session log " + file, e);
            return null;
        }
    }

    private static void force(FileChannel channel) {
        try {
            channel.force(false);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "fsync failed", e);
        }
    }

    private static void closeChannel(FileChannel channel) {
        if (channel != null) {
            force(channel);
            try {
                channel.close();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "close failed", e);
            }
        }
    }

    private record Pending(Path file, String line, long seq) {
    }
}
