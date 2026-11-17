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
package com.willwinder.ugs.nbp.bridge.eta;

import com.willwinder.ugs.nbp.bridge.job.JobModel;
import com.willwinder.ugs.nbp.bridge.job.Json;
import com.willwinder.ugs.nbp.bridge.job.MachineProfile;
import com.willwinder.ugs.nbp.bridge.job.PlannerModel;
import com.willwinder.ugs.nbp.bridge.job.estimate.Estimate;
import com.willwinder.ugs.nbp.bridge.job.estimate.MotionSettings;
import com.willwinder.ugs.nbp.bridge.job.estimate.TimeEstimator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Time estimates of job files ({@link TimeEstimator}), computed on a background thread and cached by file SHA-256,
 * motion settings and work offset (rounded to 1 mm). Shared by the API ({@code GET /file/estimate}, live ETA) and
 * the notifier.
 */
public final class EstimateCache implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(EstimateCache.class.getName());
    private static final int KEEP = 6;

    private final Supplier<MachineProfile> profile;
    private final ExecutorService worker;
    private final Map<String, CompletableFuture<Estimate>> estimates = lru(KEEP);
    private final Map<String, JobModel> models = lru(2);

    public EstimateCache(Supplier<MachineProfile> profile) {
        this.profile = profile;
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ugs-bridge-estimate");
            t.setDaemon(true);
            return t;
        });
    }

    private static <K, V> Map<K, V> lru(int keep) {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> e) {
                return size() > keep;
            }
        };
    }

    /**
     * What the controller has told the bridge: {@code $$} values ({@code "$110" -> 10000.0}) and the planner depth from
     * {@code $I}; either may be empty/null.
     */
    public record Controller(Map<String, Double> settings, Integer plannerBlocks, double[] wco) {
        public static final Controller NONE = new Controller(Map.of(), null, null);
    }

    /** The motion settings an estimate would use now. */
    public MotionSettings settings(Controller c) {
        MachineProfile p = profile.get();
        int blocks = c.plannerBlocks() != null ? c.plannerBlocks() : PlannerModel.forProfile(p).plannerBlocks();
        return MotionSettings.fromController(c.settings(), p, blocks);
    }

    /**
     * The estimate if it is ready; otherwise starts computing it and returns empty.
     *
     * @param sha256 the file's SHA-256 if known (else it is computed from the file)
     */
    public Optional<Estimate> peek(Path file, String sha256, Controller c) {
        CompletableFuture<Estimate> f = request(file, sha256, c);
        if (f != null && f.isDone() && !f.isCompletedExceptionally()) {
            return Optional.ofNullable(f.join());
        }
        return Optional.empty();
    }

    /** Waits up to {@code timeoutMs} for the estimate. Empty on timeout or failure. */
    public Optional<Estimate> get(Path file, String sha256, Controller c, long timeoutMs) {
        CompletableFuture<Estimate> f = request(file, sha256, c);
        if (f == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(f.get(timeoutMs, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException e) {
            return Optional.empty();
        }
    }

    private CompletableFuture<Estimate> request(Path file, String sha256, Controller c) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        String sha = sha256;
        if (sha == null || sha.isBlank()) {
            try {
                sha = Json.sha256Hex(Files.readAllBytes(file));
            } catch (IOException e) {
                return null;
            }
        }
        MotionSettings s = settings(c);
        double[] wco = c.wco();
        String key = sha + "|" + s.cacheKey() + "|" + (wco == null ? "-" : String.format(Locale.ROOT, "%.0f,%.0f,%.0f",
                wco[0], wco[1], wco[2]));
        String fileSha = sha;
        synchronized (this) {
            CompletableFuture<Estimate> f = estimates.get(key);
            if (f == null || f.isCompletedExceptionally()) {
                f = CompletableFuture.supplyAsync(() -> compute(file, fileSha, s, wco), worker);
                estimates.put(key, f);
            }
            return f;
        }
    }

    private Estimate compute(Path file, String sha, MotionSettings s, double[] wco) {
        try {
            JobModel job;
            synchronized (models) {
                job = models.get(sha);
            }
            if (job == null) {
                job = JobModel.load(file);
                if (!sha.equals(job.sha256())) {
                    LOGGER.info(() -> "file changed while estimating: " + file);
                }
                synchronized (models) {
                    models.put(job.sha256(), job);
                }
            }
            return TimeEstimator.estimate(job, new TimeEstimator.Inputs(s, wco, null, profile.get(), false));
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Time estimate failed for " + file, e);
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
