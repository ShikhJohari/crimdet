# Criminal Face Detection System — Implementation Plan

## Context

College/academic project to build a desktop criminal face detection system in Java. The system captures live webcam feed, detects faces in real-time, matches them against a criminal database using neural network face embeddings, and alerts on matches. Goal: polished, complete feature set with good UX.

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17+ |
| Build | Gradle (Kotlin DSL) |
| GUI | JavaFX + AtlantaFX (Nord Dark theme) + Ikonli (Material Design icons) + custom CSS |
| Face Detection | DJL (Deep Java Library) — RetinaFace model |
| Face Recognition | DJL — ArcFace model (float[512] embeddings) |
| Webcam | JavaCV (OpenCVFrameGrabber / FFmpegFrameGrabber) |
| Database | H2 embedded (file-based, zero config) |
| DB Access | JDBI (SQL-first, lightweight) |
| Connection Pool | HikariCP |
| Logging | Logback |

## Architecture

MVC + Service + Repository layers. Non-modular (no `module-info.java` — DJL/JavaCV cause module conflicts).

```
com.crimdet/
├── App.java
├── config/DatabaseConfig.java
├── model/          (Criminal, CriminalStatus, CriminalPhoto, FaceEmbedding, DetectionLog, DetectedFace, MatchResult)
├── repository/     (CriminalRepository, CriminalPhotoRepository, FaceEmbeddingRepository, DetectionLogRepository)
├── service/        (CriminalService, FaceDetectionService, FaceEmbeddingService, FaceMatchingService, WebcamService, AlertService, StatsService)
├── controller/     (MainController, DashboardController, LiveMonitorController, CriminalListController, CriminalFormController, DetectionHistoryController)
└── util/           (EmbeddingUtils, ImageUtils, CsvExporter)
```

## Data Model (H2)

- `criminals`: id, name, crime_type, description, status (WANTED/ARRESTED/RELEASED), created_at, updated_at
- `criminal_photos`: id, criminal_id, photo_data (BLOB), created_at
- `face_embeddings`: id, criminal_id, photo_id, embedding (BLOB — serialized float[512]), created_at
- `detection_logs`: id, criminal_id, confidence, screenshot (BLOB), detected_at, notes

## Face Processing Pipeline

```
Webcam (30fps) → Face Detection (every 3rd frame) → Embedding Extraction → DB Matching → Alert
  JavaCV            DJL RetinaFace                    DJL ArcFace           Cosine sim ≥ 0.6
```

Threading: capture thread → processing thread (detection+matching) → UI thread (display+alerts). All ML inference off the JavaFX thread.

## UI Screens (5 screens, sidebar nav)

1. **Dashboard** — stats cards (total criminals, wanted count, today's detections), recent alerts list, quick actions
2. **Live Monitor** — webcam feed with bounding box overlay (green=unknown, red=match), right panel alert feed, confidence threshold slider, start/stop
3. **Criminal Records** — searchable/filterable table, row click → edit
4. **Criminal Form** — name/crime type/description/status fields, multi-photo upload + webcam capture button (snap still), auto face enrollment on save
5. **Detection History** — chronological detection log table, date/confidence filters, CSV export

---

## Implementation Phases

### Phase 0: Project Skeleton
**Goal**: Gradle builds, JavaFX window opens with AtlantaFX theme.

**Files**:
- `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`
- `src/main/java/com/crimdet/App.java`

**Key**: Non-modular setup, `--add-opens` flags for JavaFX in `build.gradle.kts`. All deps declared upfront.

**Verify**: `./gradlew run` opens a dark-themed empty window.

---

### Phase 1: Database Layer
**Goal**: H2 embedded starts, schema created, JDBI repos work.

**Files**:
- `config/DatabaseConfig.java` — HikariCP + JDBI factory, `jdbc:h2:./data/crimdet`
- `resources/db/schema.sql` — DDL for all 4 tables
- `model/Criminal.java`, `CriminalStatus.java`, `CriminalPhoto.java`, `FaceEmbedding.java`, `DetectionLog.java`
- `repository/CriminalRepository.java`, `CriminalPhotoRepository.java`, `FaceEmbeddingRepository.java`, `DetectionLogRepository.java`

**Verify**: JUnit test — in-memory H2, insert criminal, query back.

**Depends on**: Phase 0

---

### Phase 2: UI Shell — Sidebar Navigation
**Goal**: 5-screen sidebar navigation, placeholder content, AtlantaFX applied globally.

**Files**:
- `controller/MainController.java` — sidebar + StackPane content routing
- `resources/fxml/main.fxml`, `dashboard.fxml`, `live-monitor.fxml`, `criminal-list.fxml`, `criminal-form.fxml`, `detection-history.fxml` (placeholders)
- `resources/css/application.css` — custom card styles, sidebar hover, shadows
- Stub controllers for all 5 screens

**Sidebar**: Ikonli MaterialDesign icons, active state highlight, vertical layout.

**Verify**: `./gradlew run` — dark-themed window, click sidebar icons to switch screens.

**Depends on**: Phase 0

---

### Phase 3: Criminal Records CRUD
**Goal**: Full list/add/edit/delete for criminals via GUI backed by H2.

**Files**:
- `service/CriminalService.java` — CRUD business logic
- `util/ImageUtils.java` — resize, convert, crop helpers
- Flesh out `CriminalListController` (TableView, search, filter) and `CriminalFormController` (form fields, photo upload + webcam capture, validation)
- Update FXML files

**Verify**: Add criminal with photo via form, appears in list, edit, delete, data persists across restarts.

**Depends on**: Phase 1, Phase 2

---

### Phase 4: Face Detection (DJL RetinaFace) — HIGHEST RISK
**Goal**: Given an image, detect faces and return bounding boxes.

**Files**:
- `service/FaceDetectionService.java` — DJL Criteria for RetinaFace, `detectFaces(BufferedImage)` method
- `model/DetectedFace.java` — record with BoundingBox, probability, croppedFace

**Key risks**:
- DJL model zoo may not have RetinaFace directly — fallback: PaddlePaddle engine or manual ONNX model download
- First run downloads ~100MB model

**Verify**: Test with known image, assert correct face count.

**Depends on**: Phase 0

---

### Phase 5: Face Embeddings (ArcFace)
**Goal**: Cropped face → float[512] embedding vector.

**Files**:
- `service/FaceEmbeddingService.java` — DJL Criteria for ArcFace, `extractEmbedding(BufferedImage)`, `enrollCriminal()`
- `util/EmbeddingUtils.java` — cosine similarity, float[] ↔ byte[] serialization

**Integration**: Criminal form auto-extracts embeddings on photo save. Validates exactly 1 face per photo.

**Verify**: Same-person similarity > 0.6, different-person < 0.4.

**Depends on**: Phase 4, Phase 1

---

### Phase 6: Face Matching
**Goal**: Compare query embedding against all enrolled criminals.

**Files**:
- `service/FaceMatchingService.java` — in-memory embedding cache, `findMatches(float[], threshold)`, cache refresh
- `model/MatchResult.java` — record with criminalId, name, confidence, status

**Verify**: Enroll 3 criminals, query with known face, correct match returned highest.

**Depends on**: Phase 5

---

### Phase 7: Webcam Service — HIGH RISK
**Goal**: Live webcam feed displayed in JavaFX.

**Files**:
- `service/WebcamService.java` — `OpenCVFrameGrabber` (fallback: `FFmpegFrameGrabber` with avfoundation on macOS), daemon capture thread, `Java2DFrameConverter`, frame callback

**Threading**: Capture thread → `AtomicReference<BufferedImage>` → `AnimationTimer` calls `Platform.runLater()` to update `ImageView` via `SwingFXUtils.toFXImage()`.

**Verify**: Navigate to Live Monitor, see smooth webcam feed.

**Depends on**: Phase 0, Phase 2

---

### Phase 8: Live Monitor (Full Integration)
**Goal**: Real-time face detection + matching on webcam feed with visual alerts.

**Files**:
- Flesh out `LiveMonitorController` — webcam ImageView + Canvas overlay for bounding boxes, right-panel alert feed, confidence slider, start/stop
- `service/AlertService.java` — alert sound, 30-second debounce per criminal, log to DB
- `resources/sounds/alert.wav`
- Update `live-monitor.fxml`

**Bounding boxes**: Canvas overlay — green = unknown, red = match with name + confidence label.

**Verify**: Enroll a criminal, show their photo to webcam, see red bounding box + alert sound + log entry.

**Depends on**: Phase 4, 5, 6, 7

---

### Phase 9: Dashboard
**Goal**: At-a-glance stats and recent activity.

**Files**:
- `service/StatsService.java` — query methods for dashboard metrics
- Flesh out `DashboardController` — stats cards, recent alerts list, quick action buttons
- Update `dashboard.fxml`

**Verify**: Counts match DB state, update after adding criminals.

**Depends on**: Phase 1, 2, 3

---

### Phase 10: Detection History
**Goal**: Searchable log with export.

**Files**:
- Flesh out `DetectionHistoryController` — TableView, date/confidence filters, pagination, CSV export
- `util/CsvExporter.java`
- Update `detection-history.fxml`

**Verify**: Detections from live monitor appear, export CSV opens in spreadsheet.

**Depends on**: Phase 1, 2, 8

---

### Phase 11: Polish & Demo Prep
**Goal**: Smooth, demo-ready application.

**What**:
- Splash screen with model loading progress
- Global error handler (friendly dialog instead of crash)
- CSS polish (transitions, hover effects, animations)
- App icon
- Graceful shutdown (stop webcam, close models, close DB)

**Verify**: Full end-to-end demo: launch → dashboard → add criminal → live monitor → detect match → alert → view history → export → clean shutdown.

**Depends on**: All prior phases

---

## Build Order (Critical Path)

```
Phase 0 (Skeleton)
├── Phase 1 (DB)         ─── Phase 3 (CRUD UI) ─── Phase 9 (Dashboard)
├── Phase 2 (UI Shell)  ─┘
├── Phase 4 (Face Det)  ─── Phase 5 (Embeddings) ─── Phase 6 (Matching)
├── Phase 7 (Webcam)
│
└──────────── Phase 8 (Live Monitor) ─── Phase 10 (History) ─── Phase 11 (Polish)
```

**Do Phase 4 early** — highest risk item. If DJL models don't load, need time to troubleshoot.

## Risks

| Risk | Severity | Mitigation |
|---|---|---|
| DJL RetinaFace not in model zoo | HIGH | Fallback: PaddlePaddle engine or manual ONNX model |
| DJL model first-download latency | MEDIUM | Splash screen with progress, pre-download in dev |
| JavaCV webcam on macOS | MEDIUM | Try OpenCVFrameGrabber, fallback FFmpeg+avfoundation, clear error on permission denied |
| DJL + JavaCV classpath conflicts | MEDIUM | Test together in Phase 4, pin native versions if needed |
| JavaFX thread blocking | MEDIUM | All ML inference on background threads, `Platform.runLater()` for UI updates |

## Testing Strategy

**Unit tests (must-have)**: EmbeddingUtils, ImageUtils, CriminalRepository, FaceMatchingService

**Integration tests (should-have)**: FaceDetectionService (known image → correct face count), FaceEmbeddingService (face → 512-length vector), CriminalService (full add+enroll pipeline)

**Manual testing**: Full demo flow on demo day

## File Count

~48 hand-written files + Gradle wrapper + resources (icons, sounds, test images)

## Complete File Manifest

```
crimdet/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/...
└── src/
    ├── main/
    │   ├── java/com/crimdet/
    │   │   ├── App.java
    │   │   ├── config/
    │   │   │   └── DatabaseConfig.java
    │   │   ├── model/
    │   │   │   ├── Criminal.java
    │   │   │   ├── CriminalStatus.java
    │   │   │   ├── CriminalPhoto.java
    │   │   │   ├── FaceEmbedding.java
    │   │   │   ├── DetectionLog.java
    │   │   │   ├── DetectedFace.java
    │   │   │   └── MatchResult.java
    │   │   ├── repository/
    │   │   │   ├── CriminalRepository.java
    │   │   │   ├── CriminalPhotoRepository.java
    │   │   │   ├── FaceEmbeddingRepository.java
    │   │   │   └── DetectionLogRepository.java
    │   │   ├── service/
    │   │   │   ├── CriminalService.java
    │   │   │   ├── FaceDetectionService.java
    │   │   │   ├── FaceEmbeddingService.java
    │   │   │   ├── FaceMatchingService.java
    │   │   │   ├── WebcamService.java
    │   │   │   ├── AlertService.java
    │   │   │   └── StatsService.java
    │   │   ├── controller/
    │   │   │   ├── MainController.java
    │   │   │   ├── DashboardController.java
    │   │   │   ├── LiveMonitorController.java
    │   │   │   ├── CriminalListController.java
    │   │   │   ├── CriminalFormController.java
    │   │   │   └── DetectionHistoryController.java
    │   │   └── util/
    │   │       ├── EmbeddingUtils.java
    │   │       ├── ImageUtils.java
    │   │       └── CsvExporter.java
    │   └── resources/
    │       ├── fxml/
    │       │   ├── main.fxml
    │       │   ├── dashboard.fxml
    │       │   ├── live-monitor.fxml
    │       │   ├── criminal-list.fxml
    │       │   ├── criminal-form.fxml
    │       │   └── detection-history.fxml
    │       ├── css/
    │       │   ├── application.css
    │       │   └── splash.css
    │       ├── db/
    │       │   └── schema.sql
    │       ├── sounds/
    │       │   └── alert.wav
    │       └── images/
    │           └── app-icon.png
    └── test/
        └── java/com/crimdet/
            ├── repository/
            │   └── CriminalRepositoryTest.java
            ├── service/
            │   ├── FaceDetectionServiceTest.java
            │   ├── FaceEmbeddingServiceTest.java
            │   ├── FaceMatchingServiceTest.java
            │   └── CriminalServiceTest.java
            └── util/
                ├── EmbeddingUtilsTest.java
                └── ImageUtilsTest.java
```
