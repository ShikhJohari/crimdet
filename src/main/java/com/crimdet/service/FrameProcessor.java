package com.crimdet.service;

import com.crimdet.model.RecognizedFace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Offer-and-drop frame pipeline for the live monitor.
 *
 * Callers {@link #submit} frames at camera rate; the worker drains them at
 * detection rate. Capacity is fixed at one: a newer frame always replaces
 * any queued frame, so the detector is never behind real-time. Every
 * replaced frame increments the dropped-frames counter.
 *
 * Results fan out to a single {@link Consumer} listener on the worker
 * thread — callers that update UI are responsible for marshaling to the
 * FX thread themselves.
 */
public final class FrameProcessor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FrameProcessor.class);
    private static final int STATS_WINDOW = 30;

    private final Function<BufferedImage, List<RecognizedFace>> detector;
    private final ExecutorService worker;

    private final AtomicReference<BufferedImage> pending = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(true);

    private volatile Consumer<FrameResult> listener;

    private final AtomicLong processedFrames = new AtomicLong();
    private final AtomicLong droppedFrames = new AtomicLong();
    private final Deque<Sample> samples = new ArrayDeque<>(STATS_WINDOW);
    private final Object samplesLock = new Object();

    public FrameProcessor(FaceRecognitionPipeline pipeline) {
        this(pipeline::process);
    }

    /** Test hook: inject an arbitrary detector function. */
    FrameProcessor(Function<BufferedImage, List<RecognizedFace>> detector) {
        this.detector = detector;
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "frame-processor-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public void setListener(Consumer<FrameResult> listener) {
        this.listener = listener;
    }

    /**
     * Offer a frame for detection.
     *
     * @return {@code true} if the frame went into an empty slot; {@code false}
     *         if it replaced a queued frame (which was then dropped) or the
     *         processor is closed or the frame is null.
     */
    public boolean submit(BufferedImage frame) {
        if (frame == null || !running.get()) return false;
        BufferedImage prev = pending.getAndSet(frame);
        boolean replacedQueued = prev != null;
        if (replacedQueued) droppedFrames.incrementAndGet();
        kickWorker();
        return !replacedQueued;
    }

    public FrameStats getStats() {
        double avgLatencyMs;
        double fps;
        synchronized (samplesLock) {
            if (samples.isEmpty()) {
                avgLatencyMs = 0.0;
                fps = 0.0;
            } else {
                double sum = 0.0;
                for (Sample s : samples) sum += s.latencyMs;
                avgLatencyMs = sum / samples.size();
                if (samples.size() >= 2) {
                    long spanNanos = samples.peekLast().completedNanos - samples.peekFirst().completedNanos;
                    fps = spanNanos > 0 ? (samples.size() - 1) * 1_000_000_000.0 / spanNanos : 0.0;
                } else {
                    fps = avgLatencyMs > 0 ? 1000.0 / avgLatencyMs : 0.0;
                }
            }
        }
        return new FrameStats(processedFrames.get(), droppedFrames.get(), avgLatencyMs, fps);
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        worker.shutdown();
        try {
            if (!worker.awaitTermination(2, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
        pending.set(null);
    }

    private void kickWorker() {
        if (scheduled.compareAndSet(false, true)) {
            try {
                worker.submit(this::runCycle);
            } catch (RejectedExecutionException e) {
                scheduled.set(false); // worker already shut down
            }
        }
    }

    private void runCycle() {
        scheduled.set(false);
        BufferedImage frame = pending.getAndSet(null);
        if (frame == null) return;

        long startNanos = System.nanoTime();
        List<RecognizedFace> faces;
        try {
            faces = detector.apply(frame);
        } catch (Exception e) {
            log.error("frame detection failed", e);
            return;
        }
        long latencyMs = Math.max(1, (System.nanoTime() - startNanos) / 1_000_000L);
        recordSample(latencyMs);
        processedFrames.incrementAndGet();

        Consumer<FrameResult> l = listener;
        if (l != null) {
            try {
                l.accept(new FrameResult(frame, faces, latencyMs));
            } catch (Exception e) {
                log.error("frame listener threw", e);
            }
        }

        // A frame that arrived while we were processing needs its own cycle.
        if (running.get() && pending.get() != null) kickWorker();
    }

    private void recordSample(long latencyMs) {
        synchronized (samplesLock) {
            if (samples.size() == STATS_WINDOW) samples.removeFirst();
            samples.addLast(new Sample(latencyMs, System.nanoTime()));
        }
    }

    private record Sample(long latencyMs, long completedNanos) {}
}
