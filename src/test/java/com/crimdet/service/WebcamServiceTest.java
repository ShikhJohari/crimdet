package com.crimdet.service;

import org.junit.jupiter.api.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WebcamServiceTest {

    @AfterEach
    void cleanup() {
        WebcamService.shutdownAll();
    }

    @Test
    void initialStateIsStopped() {
        WebcamService svc = new WebcamService();
        assertEquals(WebcamService.State.STOPPED, svc.getState());
    }

    @Test
    void stopWhenStoppedIsNoOp() {
        WebcamService svc = new WebcamService();
        svc.stop();
        assertEquals(WebcamService.State.STOPPED, svc.getState());
    }

    @Test
    void shutdownAllWithNoInstancesIsSafe() {
        WebcamService.shutdownAll();
    }

    @Test
    void startReachesTerminalStateThenStopsCleanly() throws InterruptedException {
        WebcamService svc = new WebcamService();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<WebcamService.State> reached = new AtomicReference<>();

        svc.start(
            frame -> {},
            (oldState, newState, errorMsg) -> {
                if (newState == WebcamService.State.RUNNING || newState == WebcamService.State.ERROR) {
                    reached.set(newState);
                    latch.countDown();
                }
            }
        );

        assertTrue(latch.await(15, TimeUnit.SECONDS),
            "Should reach RUNNING or ERROR state");
        assertNotNull(reached.get());

        svc.stop();
        assertEquals(WebcamService.State.STOPPED, svc.getState());
    }

    @Test
    void doubleStartIsIdempotent() throws InterruptedException {
        WebcamService svc = new WebcamService();
        CountDownLatch latch = new CountDownLatch(1);

        svc.start(frame -> {}, (oldState, newState, errorMsg) -> {
            if (newState == WebcamService.State.RUNNING || newState == WebcamService.State.ERROR) {
                latch.countDown();
            }
        });

        // Second start while first is in progress — should be ignored
        svc.start(frame -> {}, (oldState, newState, errorMsg) -> {});

        latch.await(15, TimeUnit.SECONDS);
        svc.stop();
    }

    @Test
    void shutdownAllStopsActiveInstances() throws InterruptedException {
        WebcamService svc1 = new WebcamService();
        WebcamService svc2 = new WebcamService();
        CountDownLatch latch = new CountDownLatch(2);

        WebcamService.StateListener listener = (oldState, newState, errorMsg) -> {
            if (newState == WebcamService.State.RUNNING || newState == WebcamService.State.ERROR) {
                latch.countDown();
            }
        };

        svc1.start(frame -> {}, listener);
        svc2.start(frame -> {}, listener);

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Both should reach terminal state");

        WebcamService.shutdownAll();
        Thread.sleep(500);

        assertEquals(WebcamService.State.STOPPED, svc1.getState());
        assertEquals(WebcamService.State.STOPPED, svc2.getState());
    }
}
