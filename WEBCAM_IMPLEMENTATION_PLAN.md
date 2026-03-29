# Webcam Real-Time Criminal Detection — Full Implementation Plan

> **Context for new Claude instance:** This is the output of a full /autoplan review session (CEO + Design + Eng reviews with dual-voice subagents). The plan is APPROVED by the user. Implement it step by step. Do NOT re-plan or re-review — just build it.

**Branch:** main | **Project:** CrimDet (Java 17 / JavaFX 21 desktop app) | **Theme:** AtlantaFX Nord Dark

---

## What This App Is

A criminal face detection desktop app. Complete pipeline: YuNet/DNN/Haar face detection -> SFace 128-D embeddings -> cosine similarity matching (threshold 0.363). 5 screens: Dashboard, Live Monitor, Criminal Records, Criminal Form, Detection History. H2 embedded DB, JDBI, HikariCP. JavaCV 1.5.10 already in dependencies.

**What's missing:** The Live Monitor screen only supports static image file upload + scan. No webcam. This plan adds real-time webcam detection.

---

## Architecture Overview (Current)

```
App.java (entry point, theme, DB init, embedding migration)
  |
  +-- MainController (screen navigation, StackPane switcher, screen cache)
  |     |
  |     +-- DashboardController (stats, alerts)
  |     +-- LiveMonitorController (image upload + scan + canvas overlay)
  |     +-- CriminalListController (table, search, filter)
  |     +-- CriminalFormController (add/edit, photo validation, enrollment)
  |     +-- CriminalDetailController (read-only view)
  |     +-- DetectionHistoryController (logs, pagination, CSV export)
  |
  +-- DatabaseConfig (H2 + HikariCP + JDBI singleton)
  |
  +-- Services:
  |     FaceDetectionService (singleton, synchronized) — YuNet -> DNN -> Haar fallback
  |     FaceEmbeddingService (per-instance, synchronized) — SFace 128-D + alignCrop
  |     FaceMatchingService (per-instance, refreshCache sync, findMatches NOT sync)
  |     CriminalService (CRUD)
  |     StatsService (dashboard metrics)
  |
  +-- Repositories: Criminal, CriminalPhoto, FaceEmbedding, DetectionLog
  +-- Models: Criminal, CriminalPhoto, CriminalStatus, DetectedFace, DetectionLog, FaceEmbedding, MatchResult
  +-- Utils: EmbeddingUtils (cosine similarity, serialization), ImageUtils (resize, convert), CsvExporter
```

---

## Implementation Steps (Approved, Execute In Order)

### Step 1: Create WebcamService

**New file:** `src/main/java/com/crimdet/service/WebcamService.java` (~150 lines)

Camera lifecycle service. State machine: `STOPPED -> STARTING -> RUNNING -> STOPPED` (+ `ERROR`).

**Frame grabber strategy — OpenCV primary, FFmpeg fallback:**
1. Try `OpenCVFrameGrabber(deviceIndex)` first (wraps cv::VideoCapture, maps to AVFoundation on macOS)
2. If `start()` throws OR first 30 consecutive `grab()` return null (~1 second) -> release, try FFmpegFrameGrabber
3. FFmpegFrameGrabber config on macOS: `setFormat("avfoundation")`, `setVideoOption("video_device_index", "0")`; on Linux: `/dev/video0`
4. If both fail -> ERROR state

**Key design:**
```java
public class WebcamService {
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
    private Thread captureThread; // daemon thread
    private int deviceIndex = 0;

    // Track active instances for App.stop() cleanup
    private static final Set<WebcamService> activeInstances = ConcurrentHashMap.newKeySet();

    public synchronized void start(FrameConsumer consumer, StateListener listener);
    public synchronized void stop();
    public static void shutdownAll(); // called from App.stop()
    public State getState();
}
```

**Camera resolution:** Request 1920x1080 (1080p). Camera may negotiate different — that's OK, WritableImage allocated dynamically.

**Capture thread logic:**
1. Create grabber, set resolution, call `start()`
2. On success: `state = RUNNING`, notify listener
3. On failure: try FFmpeg fallback, if that fails too -> `state = ERROR`, notify with message: "Camera unavailable. Grant access in System Settings > Privacy & Security > Camera."
4. Grab loop: `while (!stopRequested)` -> `grabber.grab()` -> `Java2DFrameConverter.convert(frame)` -> `consumer.onFrame(bufferedImage)`
5. **CRITICAL:** Conversion from Frame to BufferedImage MUST happen on capture thread. `Frame` buffer gets overwritten on next `grab()` call. The `BufferedImage` handed off is safe to use from other threads.
6. Finally: `grabber.stop()`, `grabber.release()`, `state = STOPPED`

**Null frame handling (macOS permission / warmup):**
- Track consecutive null frames. After 30 nulls (~1 second at 30fps): if OpenCV grabber, release and try FFmpeg. If FFmpeg too, -> ERROR state.
- 30 (not 10) because USB cameras legitimately produce nulls during warmup.

**Shutdown:**
- `stop()`: set `stopRequested = true`, interrupt captureThread, join(2000ms)
- `shutdownAll()`: iterate activeInstances, call stop() on each
- `start()`: add to activeInstances. `stop()`: remove from activeInstances.

---

### Step 2: Three-Thread Frame Processing (in LiveMonitorController)

| Thread | Role | Rate |
|--------|------|------|
| Capture thread (in WebcamService) | Grabs + converts frames, delivers BufferedImage | ~30fps |
| JavaFX Application Thread | Renders frames to ImageView, draws overlay | ~30fps (coalesced) |
| Detection thread (single-thread ExecutorService) | detect -> embed -> match -> update overlay | ~2-4fps |

**Frame handoff — TWO separate AtomicReferences (fixes frame-stealing race):**
```java
private final AtomicReference<BufferedImage> displayFrame = new AtomicReference<>();
private final AtomicReference<BufferedImage> detectionFrame = new AtomicReference<>();
private volatile boolean detectionBusy = false;
private volatile boolean renderPending = false;
```

The `FrameConsumer` callback (called from capture thread) writes to BOTH:
```java
private void onCameraFrame(BufferedImage frame) {
    displayFrame.set(frame);
    detectionFrame.set(frame);

    // Display path (coalesced)
    if (!renderPending) {
        renderPending = true;
        Platform.runLater(() -> {
            renderPending = false;
            BufferedImage f = displayFrame.get();
            if (f == null) return;
            // Allocate WritableImage dynamically on first frame (or if resolution changed)
            if (webcamImage == null || webcamImage.getWidth() != f.getWidth() || webcamImage.getHeight() != f.getHeight()) {
                webcamImage = new WritableImage(f.getWidth(), f.getHeight());
            }
            SwingFXUtils.toFXImage(f, webcamImage);
            imageView.setImage(webcamImage);
            emptyImageLabel.setVisible(false);
            emptyImageLabel.setManaged(false);
        });
    }

    // Detection path (throttled by detectionBusy)
    if (!detectionBusy && detectionExecutor != null && !detectionExecutor.isShutdown()) {
        detectionExecutor.submit(this::runDetectionCycle);
    }
}
```

**No mirroring.** Security app — overlay coordinates must match detection output.

**WritableImage allocated dynamically** after first frame based on actual camera resolution — NOT hardcoded to 1080p.

---

### Step 3: Modify LiveMonitorController

**File:** `src/main/java/com/crimdet/controller/LiveMonitorController.java`

**Add state machine:**
```java
private enum MonitorMode { IDLE, WEBCAM_STARTING, WEBCAM_RUNNING, IMAGE_LOADED, SCANNING }
private MonitorMode mode = MonitorMode.IDLE;
```

**New FXML fields:**
```java
@FXML private Button webcamBtn;
```

**Button state by mode:**

| Mode | webcamBtn | uploadBtn | scanBtn | clearBtn |
|------|-----------|-----------|---------|----------|
| IDLE | "Start Webcam" (enabled, accent+icon) | enabled | disabled | disabled |
| WEBCAM_STARTING | "Starting..." (disabled) | disabled | disabled | disabled |
| WEBCAM_RUNNING | "Stop Webcam" (enabled, danger style) | disabled | disabled | enabled |
| IMAGE_LOADED | "Start Webcam" (enabled) | enabled | enabled | enabled |
| SCANNING | all disabled | | | |

**New fields:**
```java
private WebcamService webcamService;
private ExecutorService detectionExecutor;
private WritableImage webcamImage;
private final AtomicReference<BufferedImage> displayFrame = new AtomicReference<>();
private final AtomicReference<BufferedImage> detectionFrame = new AtomicReference<>();
private volatile boolean detectionBusy = false;
private volatile boolean renderPending = false;
private final Map<Long, Instant> matchCooldowns = new ConcurrentHashMap<>();
private static final Duration MATCH_COOLDOWN = Duration.ofSeconds(30);
private Set<Long> previousMatchIds = new HashSet<>();
private long lastDetectionTimeMs = 0;
```

**New methods:**

`onToggleWebcam()` — FXML action:
- If WEBCAM_RUNNING: call stopWebcam(), return to IDLE
- Else: set WEBCAM_STARTING, update UI, show "Connecting to camera..." spinner in viewport, create WebcamService if null, call start()

`onCameraFrame(BufferedImage)` — FrameConsumer (called from capture thread):
- Write to both AtomicReferences
- Schedule coalesced Platform.runLater for display
- Submit detection if !detectionBusy

`onCameraStateChanged(State, State, String)` — StateListener:
- RUNNING: Platform.runLater -> set mode WEBCAM_RUNNING, update buttons, green status dot
- ERROR: Platform.runLater -> show inline error in viewport ("Camera access denied. Grant access in System Settings > Privacy & Security > Camera, then try again."), set mode IDLE

`runDetectionCycle()` — runs on detection thread:
```java
private void runDetectionCycle() {
    if (detectionBusy) return;
    detectionBusy = true;
    try {
        BufferedImage frame = detectionFrame.getAndSet(null);
        if (frame == null) return;

        long startMs = System.currentTimeMillis();
        List<DetectedFace> faces = FaceDetectionService.getInstance().detectFaces(frame);
        List<FaceResult> results = new ArrayList<>();
        int matchCount = 0;

        for (DetectedFace face : faces) {
            float[] embedding = embeddingService.extractEmbedding(face);
            // Null out originalImage to free 1080p frame memory
            face.setOriginalImage(null);

            List<MatchResult> matches = matchingService.findMatches(embedding);
            if (!matches.isEmpty()) {
                MatchResult best = matches.get(0);
                results.add(new FaceResult(face, best));
                matchCount++;
                logMatchWithDeduplication(best, face);
            } else {
                results.add(new FaceResult(face, null));
            }
        }

        long durationMs = System.currentTimeMillis() - startMs;
        double fps = durationMs > 0 ? 1000.0 / durationMs : 0;

        final var finalResults = results;
        final int faceCount = faces.size();
        final int finalMatchCount = matchCount;

        // Only rebuild cards if match set changed
        Set<Long> currentMatchIds = results.stream()
            .filter(r -> r.match() != null)
            .map(r -> r.match().getCriminalId())
            .collect(Collectors.toSet());
        boolean cardsChanged = !currentMatchIds.equals(previousMatchIds);
        previousMatchIds = currentMatchIds;

        Platform.runLater(() -> {
            lastResults = finalResults;
            drawOverlay();
            if (cardsChanged) buildResultCards(finalResults);
            statusLabel.setText(String.format("Faces: %d | Matches: %d | Detection: %.1f fps",
                faceCount, finalMatchCount, fps));
        });
    } catch (Exception e) {
        log.error("Detection cycle failed", e);
    } finally {
        detectionBusy = false;
    }
}
```

`logMatchWithDeduplication(MatchResult, DetectedFace)` — 30s cooldown per criminal:
```java
private void logMatchWithDeduplication(MatchResult match, DetectedFace face) {
    long criminalId = match.getCriminalId();
    Instant now = Instant.now();
    Instant lastLogged = matchCooldowns.get(criminalId);
    if (lastLogged != null && Duration.between(lastLogged, now).compareTo(MATCH_COOLDOWN) < 0) {
        return;
    }
    matchCooldowns.put(criminalId, now);
    try {
        DetectionLog dl = new DetectionLog();
        dl.setCriminalId(criminalId);
        dl.setConfidence(match.getConfidence());
        dl.setScreenshot(ImageUtils.toBytes(face.getCroppedFace(), "png"));
        dl.setNotes("Webcam live detection: " + match.getCriminalName());
        detectionLogRepo.insert(dl);
    } catch (Exception e) {
        log.error("Failed to log detection", e);
    }
}
```

`stopWebcam()`:
```java
private void stopWebcam() {
    if (webcamService != null) webcamService.stop();
    if (detectionExecutor != null) {
        detectionExecutor.shutdownNow();
        try { detectionExecutor.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        detectionExecutor = null;
    }
    webcamImage = null;
    displayFrame.set(null);
    detectionFrame.set(null);
    detectionBusy = false;
    matchCooldowns.clear();
    previousMatchIds.clear();
    mode = MonitorMode.IDLE;
    // Update UI...
}
```

`onScreenDeactivated()` — called by MainController:
```java
public void onScreenDeactivated() {
    if (mode == MonitorMode.WEBCAM_RUNNING || mode == MonitorMode.WEBCAM_STARTING) {
        stopWebcam();
    }
}
```

**Integration with existing flows:**
- `onUpload()`: call `stopWebcam()` first if webcam active, then existing logic
- `onClear()`: call `stopWebcam()` if active, then existing logic
- `onScan()`: unchanged — only runs in IMAGE_LOADED mode

**Empty state messages (mode-aware):**
- IDLE: "Drop or upload an image to scan" (existing)
- WEBCAM_STARTING: ProgressIndicator + "Connecting to camera..." centered in viewport
- WEBCAM_RUNNING (no detections yet): "Scanning for faces..." in results panel
- WEBCAM_RUNNING (no matches): "No matches found. N face(s) detected."
- ERROR: Inline in viewport: "Camera access denied. Grant access in System Settings > Privacy & Security > Camera, then try again."

**Status bar during webcam mode:**
`Faces: N | Matches: N | Detection: X.X fps`

---

### Step 4: Modify live-monitor.fxml

**File:** `src/main/resources/fxml/live-monitor.fxml`

Replace the top section. Title: "Live Monitor". Button order: Webcam (with camera icon, accent style) | separator | Upload | Scan | Clear.

```xml
<top>
    <VBox spacing="12">
        <padding><Insets topRightBottomLeft="24"/></padding>
        <Label text="Live Monitor" styleClass="title-2"/>
        <HBox spacing="8" alignment="CENTER_LEFT">
            <Button fx:id="webcamBtn" text="Start Webcam" onAction="#onToggleWebcam"
                    styleClass="accent">
                <graphic><FontIcon iconLiteral="mdi2c-camera" iconSize="18"/></graphic>
            </Button>
            <Separator orientation="VERTICAL" prefHeight="24"/>
            <Button fx:id="uploadBtn" text="Upload Image" onAction="#onUpload"/>
            <Button fx:id="scanBtn" text="Scan" onAction="#onScan" disable="true"/>
            <Button fx:id="clearBtn" text="Clear" onAction="#onClear" disable="true"/>
        </HBox>
    </VBox>
</top>
```

Rest of FXML (center, right, bottom) stays the same.

---

### Step 5: Modify MainController for Screen Deactivation

**File:** `src/main/java/com/crimdet/controller/MainController.java`

Add a `currentScreenName` field and deactivation in `loadScreen()`:

```java
private String currentScreenName;

private void loadScreen(String name) {
    // Deactivate current screen if needed
    if (currentScreenName != null) {
        Object ctrl = controllerCache.get(currentScreenName);
        if (ctrl instanceof LiveMonitorController lmc) {
            lmc.onScreenDeactivated();
        }
    }

    // ... existing loadScreen logic ...

    currentScreenName = name;
    contentArea.getChildren().setAll(screen);
}
```

---

### Step 5b: Fix FaceMatchingService Thread Safety

**File:** `src/main/java/com/crimdet/service/FaceMatchingService.java`

Add `synchronized` to `findMatches()`:
```java
public synchronized List<MatchResult> findMatches(float[] queryEmbedding, double threshold) {
```

This prevents ConcurrentModificationException if refreshCache() and findMatches() run on different threads.

---

### Step 6: Modify App.java for Shutdown Cleanup

**File:** `src/main/java/com/crimdet/App.java`

```java
@Override
public void stop() {
    WebcamService.shutdownAll();
    DatabaseConfig.getInstance().close();
    log.info("CrimDet shutdown");
}
```

Ordering matters: stop webcam (which stops detection thread with awaitTermination) BEFORE closing DB.

---

### Step 7: Add CSS Styles

**File:** `src/main/resources/css/application.css`

Append:
```css
/* Webcam controls */
.webcam-active {
    -fx-background-color: -color-danger-emphasis;
    -fx-text-fill: white;
}

.webcam-dot {
    -fx-min-width: 8; -fx-min-height: 8;
    -fx-max-width: 8; -fx-max-height: 8;
    -fx-background-radius: 4;
}

.webcam-dot-active { -fx-background-color: -color-success-emphasis; }
.webcam-dot-starting { -fx-background-color: -color-warning-emphasis; }
.webcam-dot-off { -fx-background-color: -color-fg-muted; }
```

Note: use `.webcam-dot` NOT `.status-dot` — dashboard already uses that class.

---

### Step 8: Testing

**New file:** `src/test/java/com/crimdet/service/WebcamServiceTest.java`
- State transition tests (STOPPED -> STARTING -> RUNNING -> STOPPED)
- Double start idempotency
- Stop when not started (no-op)
- shutdownAll() stops all active instances

**Deduplication tests** (in existing or new test file):
- Same criminal within 30s -> not re-logged
- Same criminal after 30s -> logged again
- Different criminals -> logged independently

**Verify:**
1. `./gradlew build` compiles
2. `./gradlew test` all pass
3. `./gradlew run` manual E2E: enroll criminal, start webcam, hold photo to camera, verify match

---

## Critical Implementation Notes (From Reviews)

1. **Frame conversion on capture thread.** `OpenCVFrameGrabber.grab()` returns a `Frame` whose buffer is OVERWRITTEN on the next call. Convert to `BufferedImage` via `Java2DFrameConverter` on the capture thread BEFORE handing off. The `BufferedImage` is safe across threads.

2. **Java2DFrameConverter is NOT thread-safe.** Create one instance per WebcamService (on the capture thread). Do NOT share across threads.

3. **Two AtomicReferences, not one.** `displayFrame` and `detectionFrame` — both written by capture thread. Display path reads displayFrame, detection reads detectionFrame. Eliminates frame-stealing race.

4. **WritableImage allocated dynamically.** After first frame arrives, based on actual dimensions. Camera may negotiate different resolution than requested.

5. **Null out DetectedFace.originalImage after embedding extraction.** Each reference pins the full 1080p frame (~8MB) in memory. Call `face.setOriginalImage(null)` after `extractEmbedding(face)`.

6. **Shutdown ordering.** WebcamService.shutdownAll() -> detection thread awaitTermination(2s) -> DatabaseConfig.close(). Detection thread could be mid-DB-write.

7. **30 null frames threshold** (not 10). USB cameras legitimately produce nulls during warmup. ~1 second at 30fps.

8. **FaceMatchingService.findMatches() must be synchronized.** Plain HashMap + unsynchronized iteration + synchronized refreshCache() = ConcurrentModificationException.

---

## Files Modified/Created Summary

| File | Action | Est. Lines |
|------|--------|-----------|
| `service/WebcamService.java` | **NEW** | ~150 |
| `controller/LiveMonitorController.java` | MODIFY | ~180 new |
| `fxml/live-monitor.fxml` | MODIFY | ~15 new |
| `controller/MainController.java` | MODIFY | ~10 new |
| `service/FaceMatchingService.java` | MODIFY | ~2 |
| `App.java` | MODIFY | ~1 |
| `css/application.css` | MODIFY | ~15 new |
| `service/WebcamServiceTest.java` | **NEW** | ~80 |

**Total:** ~450 new lines, 2 new files, 5 modified files.
