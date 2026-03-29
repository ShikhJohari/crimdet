package com.crimdet.service;

import org.bytedeco.javacv.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WebcamService {

    private static final Logger log = LoggerFactory.getLogger(WebcamService.class);
    private static final int NULL_FRAME_THRESHOLD = 30;
    private static final Set<WebcamService> activeInstances = ConcurrentHashMap.newKeySet();

    public enum State { STOPPED, STARTING, RUNNING, ERROR }

    @FunctionalInterface
    public interface FrameConsumer {
        void onFrame(BufferedImage frame);
    }

    @FunctionalInterface
    public interface StateListener {
        void onStateChanged(State oldState, State newState, String errorMessage);
    }

    private volatile State state = State.STOPPED;
    private volatile boolean stopRequested = false;
    private Thread captureThread;
    private int deviceIndex = 0;

    public synchronized void start(FrameConsumer consumer, StateListener listener) {
        if (state != State.STOPPED && state != State.ERROR) {
            log.warn("WebcamService already in state {}, ignoring start()", state);
            return;
        }

        stopRequested = false;
        setState(State.STARTING, listener, null);
        activeInstances.add(this);

        captureThread = new Thread(() -> runCapture(consumer, listener), "webcam-capture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    public synchronized void stop() {
        if (state == State.STOPPED) return;

        stopRequested = true;
        if (captureThread != null) {
            captureThread.interrupt();
            try {
                captureThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
        }
        state = State.STOPPED;
        activeInstances.remove(this);
    }

    public static void shutdownAll() {
        for (WebcamService instance : Set.copyOf(activeInstances)) {
            try {
                instance.stop();
            } catch (Exception e) {
                log.error("Error stopping webcam instance", e);
            }
        }
    }

    public State getState() {
        return state;
    }

    private void runCapture(FrameConsumer consumer, StateListener listener) {
        Java2DFrameConverter converter = new Java2DFrameConverter();
        FrameGrabber grabber = null;
        boolean isOpenCV = false;

        try {
            // Try OpenCV first
            try {
                grabber = createOpenCVGrabber();
                isOpenCV = true;
            } catch (Exception e) {
                log.info("OpenCV grabber failed: {}", e.getMessage());
            }

            // If OpenCV failed, try FFmpeg
            if (grabber == null) {
                try {
                    grabber = createFFmpegGrabber();
                } catch (Exception e) {
                    log.info("FFmpeg grabber failed: {}", e.getMessage());
                    setState(State.ERROR, listener,
                        "Camera unavailable. Grant access in System Settings > Privacy & Security > Camera.");
                    return;
                }
            }

            setState(State.RUNNING, listener, null);

            int consecutiveNulls = 0;
            while (!stopRequested) {
                Frame frame = grabber.grab();
                if (frame == null || frame.image == null) {
                    consecutiveNulls++;
                    if (consecutiveNulls >= NULL_FRAME_THRESHOLD) {
                        if (isOpenCV) {
                            log.warn("{} null frames from OpenCV, trying FFmpeg fallback", consecutiveNulls);
                            try { grabber.stop(); grabber.release(); } catch (Exception ignored) {}
                            try {
                                grabber = createFFmpegGrabber();
                                isOpenCV = false;
                                consecutiveNulls = 0;
                            } catch (Exception e) {
                                setState(State.ERROR, listener,
                                    "Camera unavailable. Grant access in System Settings > Privacy & Security > Camera.");
                                return;
                            }
                        } else {
                            setState(State.ERROR, listener,
                                "Camera unavailable. Grant access in System Settings > Privacy & Security > Camera.");
                            return;
                        }
                    }
                    continue;
                }

                consecutiveNulls = 0;
                BufferedImage image = converter.convert(frame);
                if (image != null) {
                    consumer.onFrame(image);
                }
            }
        } catch (Exception e) {
            if (!stopRequested) {
                log.error("Capture thread error", e);
                setState(State.ERROR, listener, "Camera error: " + e.getMessage());
            }
        } finally {
            if (grabber != null) {
                try { grabber.stop(); grabber.release(); } catch (Exception ignored) {}
            }
        }
    }

    private FrameGrabber createOpenCVGrabber() throws FrameGrabber.Exception {
        OpenCVFrameGrabber g = new OpenCVFrameGrabber(deviceIndex);
        g.setImageWidth(1920);
        g.setImageHeight(1080);
        g.start();
        log.info("OpenCV grabber started");
        return g;
    }

    private FrameGrabber createFFmpegGrabber() throws FrameGrabber.Exception {
        String os = System.getProperty("os.name", "").toLowerCase();
        FFmpegFrameGrabber g;
        if (os.contains("mac")) {
            g = new FFmpegFrameGrabber(String.valueOf(deviceIndex));
            g.setFormat("avfoundation");
        } else {
            g = new FFmpegFrameGrabber("/dev/video" + deviceIndex);
        }
        g.setImageWidth(1920);
        g.setImageHeight(1080);
        g.start();
        log.info("FFmpeg grabber started");
        return g;
    }

    private void setState(State newState, StateListener listener, String errorMessage) {
        State oldState = this.state;
        this.state = newState;
        log.debug("WebcamService state: {} -> {}", oldState, newState);
        if (listener != null) {
            try {
                listener.onStateChanged(oldState, newState, errorMessage);
            } catch (Exception e) {
                log.error("State listener error", e);
            }
        }
    }
}
