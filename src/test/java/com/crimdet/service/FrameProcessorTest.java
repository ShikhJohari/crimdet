package com.crimdet.service;

import com.crimdet.model.RecognizedFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class FrameProcessorTest {

    private FrameProcessor processor;

    @AfterEach
    void tearDown() {
        if (processor != null) processor.close();
    }

    @Test
    void submitDeliversResultToListener() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<FrameResult> received = new AtomicReference<>();
        processor = new FrameProcessor(frame -> List.of());
        processor.setListener(r -> {
            received.set(r);
            done.countDown();
        });

        BufferedImage f = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        assertTrue(processor.submit(f));
        assertTrue(done.await(2, TimeUnit.SECONDS), "listener never fired");

        FrameResult result = received.get();
        assertSame(f, result.frame());
        assertEquals(0, result.faces().size());
        assertTrue(result.latencyMs() >= 0);
    }

    @Test
    void latestFrameWinsUnderBackpressure() throws InterruptedException {
        int total = 500;
        CountDownLatch detectorEntered = new CountDownLatch(1);
        CountDownLatch holdDetector = new CountDownLatch(1);
        AtomicInteger detectorCalls = new AtomicInteger();

        Function<BufferedImage, List<RecognizedFace>> slowDetector = frame -> {
            int n = detectorCalls.incrementAndGet();
            if (n == 1) {
                detectorEntered.countDown();
                try { holdDetector.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return List.of();
        };

        List<FrameResult> received = new CopyOnWriteArrayList<>();
        CountDownLatch secondResult = new CountDownLatch(2);
        processor = new FrameProcessor(slowDetector);
        processor.setListener(r -> {
            received.add(r);
            secondResult.countDown();
        });

        BufferedImage first = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        processor.submit(first);
        assertTrue(detectorEntered.await(2, TimeUnit.SECONDS), "detector never started");

        BufferedImage last = null;
        for (int i = 0; i < total; i++) {
            last = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            processor.submit(last);
        }

        holdDetector.countDown();
        assertTrue(secondResult.await(2, TimeUnit.SECONDS), "second cycle never completed");

        assertSame(first, received.get(0).frame(), "first submitted frame should be the first processed");
        assertSame(last, received.get(1).frame(), "latest submitted frame should be the second processed");

        FrameStats stats = processor.getStats();
        assertTrue(stats.droppedFrames() > 0, "expected dropped frames under backpressure");
        assertEquals(2, stats.processedFrames());
        assertEquals(total + 1, stats.processedFrames() + stats.droppedFrames(),
                "every submitted frame should be counted as processed or dropped");
    }

    @Test
    void statsReportLatencyAndFps() throws InterruptedException {
        int cycles = 10;
        CountDownLatch done = new CountDownLatch(cycles);
        processor = new FrameProcessor(frame -> {
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return List.of();
        });
        processor.setListener(r -> done.countDown());

        for (int i = 0; i < cycles; i++) {
            processor.submit(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB));
            Thread.sleep(15); // let each cycle drain before queuing the next
        }
        assertTrue(done.await(3, TimeUnit.SECONDS), "not all cycles completed");

        FrameStats stats = processor.getStats();
        assertEquals(cycles, stats.processedFrames());
        assertTrue(stats.avgLatencyMs() >= 1, "avg latency should reflect detector sleep");
        assertTrue(stats.fps() > 0, "fps should be positive after multiple samples");
    }

    @Test
    void closeDrainsInBoundedTime() throws InterruptedException {
        processor = new FrameProcessor(frame -> List.of());
        for (int i = 0; i < 100; i++) {
            processor.submit(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB));
        }
        long start = System.currentTimeMillis();
        processor.close();
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 2500, "close() took too long: " + elapsed + " ms");
    }

    @Test
    void submitAfterCloseIsRejected() {
        processor = new FrameProcessor(frame -> List.of());
        processor.close();
        assertFalse(processor.submit(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)));
    }

    @Test
    void submitNullFrameIsRejected() {
        processor = new FrameProcessor(frame -> List.of());
        assertFalse(processor.submit(null));
        assertEquals(0, processor.getStats().processedFrames());
        assertEquals(0, processor.getStats().droppedFrames());
    }

    @Test
    void listenerExceptionDoesNotKillWorker() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch both = new CountDownLatch(2);
        List<Throwable> observed = new ArrayList<>();
        processor = new FrameProcessor(frame -> List.of());
        processor.setListener(r -> {
            both.countDown();
            int n = calls.incrementAndGet();
            if (n == 1) throw new RuntimeException("boom");
        });
        processor.submit(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB));
        Thread.sleep(50);
        processor.submit(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB));
        assertTrue(both.await(2, TimeUnit.SECONDS), "worker did not process second frame after listener threw");
        assertTrue(observed.isEmpty()); // listener errors are swallowed (logged)
    }
}
