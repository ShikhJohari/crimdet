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

## Design Tokens

AtlantaFX Nord Dark provides the base. Use these CSS variables consistently — don't hardcode hex values.

**Color roles** (from PRD Section 9.1, mapped to AtlantaFX tokens):
| Role | AtlantaFX Token | Hex | Usage |
|---|---|---|---|
| Background | `-color-bg-default` | #2E3440 | Main content area |
| Surface | `-color-bg-subtle` | #3B4252 | Cards, sidebar, elevated panels |
| Accent | `-color-accent-emphasis` | #88C0D0 | Active nav, primary buttons, links |
| Danger | `-color-danger-emphasis` | #BF616A | WANTED badge, matched bounding box, delete |
| Warning | `-color-warning-emphasis` | #EBCB8B | ARRESTED badge, detection count >0 |
| Success | `-color-success-emphasis` | #A3BE8C | RELEASED badge, unknown bounding box, face validated |
| Text primary | `-color-fg-default` | #ECEFF4 | Body text |
| Text muted | `-color-fg-muted` | #D8DEE9 | Captions, empty state text, timestamps |

**Typography scale** (AtlantaFX styleClass names):
| Usage | StyleClass | Approx Size |
|---|---|---|
| Screen title | `title-2` | 20px, bold |
| Card header | `title-3` | 16px, bold |
| Body text | (default) | 14px |
| Caption / muted | `text-muted` | 12px |
| Stat number | `title-1` | 28px, bold |

**Spacing scale**: Use multiples of 8px. Padding: 8, 16, 24. Gaps: 4, 8, 12, 16. Margins between sections: 16, 24.

**Elevation**: Cards get `dropshadow(gaussian, rgba(0,0,0,0.15), 4, 0, 0, 1)`. Sidebar gets right border only, no shadow. Dialogs use default system shadow.

**Border radius**: 8px for cards and panels, 6px for buttons and inputs, 4px for badges.

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

## UI Screens (6 screens, sidebar nav)

1. **Command Center (Dashboard)** — security-domain language, not generic SaaS:
   - **Threat Overview cards**: "Active Threats" (wanted count, red accent), "Database Size" (total criminals), "Today's Detections" (count, amber if >0)
   - Cards use left-border color accent (red/blue/amber) instead of generic boxes
   - **Monitoring Status**: live indicator showing "Monitoring: ACTIVE" (green pulse) or "Monitoring: OFFLINE" (gray) — gives the dashboard a command-center feel
   - **Recent Alerts**: timeline-style feed (not a plain list) — each entry has criminal thumbnail, name, confidence bar (visual, not just number), timestamp
   - **Quick Actions**: "Begin Surveillance" (not "Start Monitoring"), "Register Suspect" (not "Add Criminal")
2. **Live Monitor** — webcam feed with bounding box overlay (green=unknown, red=match with name+confidence label), right panel alert feed, confidence threshold slider, start/stop, FPS counter, "Monitoring active" pulse indicator, scanned faces counter
3. **Criminal Records** — searchable/filterable table with criminal thumbnail in first column, status shown as colored badge (red=WANTED, amber=ARRESTED, green=RELEASED), row click → detail view
4. **Criminal Detail** — read-only profile: header with large name + status badge + crime type, photo gallery, description, timestamps, mini detection-history table for this criminal, "Edit" and "Back" action bar
5. **Criminal Form** — name/crime type/description/status fields, multi-photo upload + webcam capture button (snap still), per-photo face validation with green check/red X overlay, auto face enrollment on save
6. **Detection History** — chronological detection log table with screenshot thumbnails, date/confidence filters, CSV export, pagination

## Window Sizing & Keyboard Navigation

**Minimum window size**: 1024x700. Set via `stage.setMinWidth(1024); stage.setMinHeight(700);`

**Default size**: 1200x800.

**Resize behavior**:
- Sidebar stays fixed width (220px), content area fills remaining space
- Tables use column resize policy: last column fills remaining width
- Webcam feed scales proportionally within its container
- Photo grid wraps (FlowPane) — shows fewer columns at narrow widths

**Keyboard navigation** (JavaFX defaults + minimal additions):
- Tab cycles through sidebar buttons, then content area controls
- Enter/Space activates focused button or opens focused table row
- Escape in Criminal Form = Back (cancel)
- Escape in Criminal Detail = Back
- Focus ring visible on all interactive elements (AtlantaFX provides this)

## Interaction States

Every feature must handle loading, empty, error, and success states. No screen should ever show a bare empty table or unexplained blank area. Empty states are features — they guide the user to their next action.

| Feature | Loading | Empty | Error | Success |
|---|---|---|---|---|
| **Splash / ML models** | Progress bar: "Loading face detection models..." with percentage | N/A | "Failed to load models. Check internet connection." + Retry button | Transitions to Dashboard |
| **Dashboard stats** | Shimmer placeholders in stat cards | Cards show "0" with muted subtitle "No criminals added yet" | "Could not load stats" inline text | Cards show live counts |
| **Dashboard recent alerts** | Spinner below stats | "No detections yet. Start monitoring to detect matches." + "Start Monitoring" button | "Could not load alerts" inline | Alert list with thumbnails |
| **Criminal list** | Spinner centered in table area | Illustration/icon + "No criminal records yet." + "Add your first criminal" primary button | "Could not load records" + Retry | Populated table |
| **Criminal list (filtered)** | N/A | "No results for '[query]'" + "Clear filters" link | N/A | Filtered results shown |
| **Criminal form save** | "Save" button shows spinner, fields disabled | N/A | Red inline message: "Failed to save. [reason]" Fields re-enabled | Navigate back to list with success notification |
| **Photo upload** | Thumbnail placeholder with spinner per photo | "No photos added yet." (muted text) | "Could not read [filename]. Try a different image." | Photo thumbnail appears in grid |
| **Photo face validation** | Spinner overlay on photo tile: "Detecting face..." | N/A | "No face detected" or "Multiple faces detected — use a single-face photo" on the photo tile | Green check overlay on tile |
| **Webcam feed** | "Starting camera..." with spinner in feed area | N/A | "Camera not available. Check permissions in System Settings." + camera icon | Live feed renders |
| **Live Monitor alert feed** | N/A | "No matches detected this session." (muted, in alert panel) | N/A | Alert cards stack in feed |
| **Detection History** | Spinner centered in table area | "No detections recorded yet. Matches appear here after monitoring." | "Could not load history" + Retry | Populated table |
| **Detection History (filtered)** | N/A | "No detections match your filters." + "Clear filters" link | N/A | Filtered results shown |

---

## Implementation Phases

## User Journey — Emotional Storyboard

Key moments that define whether this app feels intentional vs generated. These guide Phase 11 polish.

| Step | User Does | User Feels | What Supports It |
|---|---|---|---|
| **First launch** | Runs app for first time | Anticipation → mild uncertainty ("is it working?") | Splash screen with model download progress gives confidence. Dashboard loads with empty state that welcomes: "Welcome to CrimDet. Add your first criminal to get started." Not a blank void. |
| **First enrollment** | Adds first criminal with photo | Productive → rewarded ("it accepted my data") | Photo face validation gives instant green checkmark. Save shows brief success toast/notification. Criminal appears in list immediately. |
| **Starting monitoring** | Clicks Start on Live Monitor | Excitement → watchfulness | Camera starts with smooth transition (not jarring). Green bounding boxes on faces = system is actively working. Subtle "Monitoring active" indicator with pulse animation. |
| **First match** | Criminal face appears in webcam | Alert → validation ("it actually works!") | Red bounding box with name + confidence is bold and unmissable. Alert sound is short, authoritative (not annoying). Alert card slides into feed with criminal photo. This is THE demo moment — make it dramatic. |
| **Long idle monitoring** | No matches for 10+ minutes | Doubt ("is it still running?") | FPS counter + "Monitoring active" pulse confirm system is live. Green boxes on detected faces prove detection is working. Status bar: "X faces scanned, 0 matches" gives ongoing confidence. |
| **Reviewing history** | Browses detection logs | Analytical, reflective | Screenshot thumbnails make each detection tangible. Export to CSV feels professional — "I can report on this." |

---

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
**Goal**: 6-screen sidebar navigation + cross-nav, placeholder content, AtlantaFX applied globally.

**Files**:
- `controller/MainController.java` — sidebar + StackPane content routing + cross-navigation methods
- `resources/fxml/main.fxml`, `dashboard.fxml`, `live-monitor.fxml`, `criminal-list.fxml`, `criminal-form.fxml`, `criminal-detail.fxml`, `detection-history.fxml` (placeholders)
- `resources/css/application.css` — custom card styles, sidebar hover, shadows
- Stub controllers for all 6 screens (including `CriminalDetailController`)

**Sidebar**: Ikonli MaterialDesign icons, active state highlight, vertical layout.

**Cross-navigation** (not sidebar-driven):
- Dashboard "Begin Surveillance" → Live Monitor + auto-start webcam (one click to go live)
- Dashboard "Register Suspect" → Criminal Form (new)
- Alert feed entry "View Record" → Criminal Detail (read-only)
- Criminal Detail "Edit" → Criminal Form (edit mode)
- Criminal list row click → Criminal Detail (not edit form)
- Criminal Detail "Back" → previous screen (criminal list or alert source)

**Criminal Detail view** (read-only):
- Header: name, status badge, crime type
- Photo gallery (all enrolled photos)
- Description, created/updated timestamps
- Detection history for this criminal (mini-table of past matches)
- Action bar: "Edit" button, "Back" button
- Purpose: separates viewing from editing to prevent accidental modifications during alert review

**Verify**: `./gradlew run` — dark-themed window, click sidebar icons to switch screens. Cross-nav links work.

**Depends on**: Phase 0

---

### Phase 3: Criminal Records CRUD
**Goal**: Full list/add/edit/delete for criminals via GUI backed by H2.

**Files**:
- `service/CriminalService.java` — CRUD business logic
- `util/ImageUtils.java` — resize, convert, crop helpers
- Flesh out `CriminalListController` (TableView, search, filter) and `CriminalFormController` (form fields, photo upload + webcam capture, validation)
- `CriminalDetailController` — read-only criminal profile view
- Update FXML files

**Note**: Webcam capture in the form is deferred to Phase 8 (after WebcamService is proven). Phase 3 is file-upload only.

**Verify**: Add criminal with photo via form (file upload), appears in list, edit, delete, data persists across restarts. Row click → detail view (read-only) → edit button → form.

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

**Ownership model**: WebcamService is a singleton. One owner (screen) at a time.
- `acquire(owner)` — starts camera, returns true. Fails if another owner holds it.
- `release(owner)` — stops camera, makes it available.
- Live Monitor calls acquire on Start, release on Stop/navigate-away.
- Criminal Form calls acquire on "Capture" click, release on "Cancel" or "Use This Photo."
- If Live Monitor is active when Form requests camera: auto-pause monitoring, show "Camera in use — paused." Resume on release.

**Verify**: Navigate to Live Monitor, see smooth webcam feed.

**Depends on**: Phase 0, Phase 2

---

### Phase 8: Live Monitor (Full Integration)
**Goal**: Real-time face detection + matching on webcam feed with visual alerts. Also adds webcam capture to Criminal Form.

**Files**:
- Flesh out `LiveMonitorController` — webcam ImageView + Canvas overlay for bounding boxes, right-panel alert feed, confidence slider, start/stop, FPS counter, "Monitoring active" pulse indicator, scanned-faces counter
- Add webcam capture to `CriminalFormController` — inline preview below photo grid, snap/use/cancel buttons (reuses WebcamService with ownership model from Phase 7)
- `service/AlertService.java` — alert sound (short, authoritative tone — not alarm-like), 30-second debounce per criminal, log to DB
- `resources/sounds/alert.wav` — brief notification tone, ~0.5s
- Update `live-monitor.fxml`

**Bounding boxes**: Canvas overlay — green = unknown, red = match with name + confidence label.

**Status bar**: "Monitoring Active" green pulse + "Faces scanned: X | Matches: Y" — gives confidence the system is working during long idle periods.

**Alert feed**: Each entry shows criminal thumbnail, name, confidence as visual bar (not just number), timestamp. Clicking entry → Criminal Detail view.

**Verify**: Enroll a criminal, show their photo to webcam, see red bounding box + alert sound + log entry. Confirm FPS counter and scanned-faces counter update in real time.

**Depends on**: Phase 4, 5, 6, 7

---

### Phase 9: Command Center (Dashboard)
**Goal**: Security command center — at-a-glance threat overview and recent activity.

**Files**:
- `service/StatsService.java` — query methods for dashboard metrics
- Flesh out `DashboardController` — threat overview cards, monitoring status, recent alerts timeline, quick action buttons
- Update `dashboard.fxml`

**Threat overview cards** (not generic stat cards):
- "Active Threats" — wanted count, danger-red left border accent
- "Database Size" — total criminals, accent-blue left border
- "Today's Detections" — count, warning-amber left border if >0
- Each card: icon + large number (title-1) + subtitle label

**Monitoring status indicator**: "Surveillance: ACTIVE" with green pulse dot, or "Surveillance: OFFLINE" with gray dot. Reflects whether WebcamService is running.

**Recent alerts**: Timeline-style feed (not a flat list). Each entry: criminal thumbnail, name, confidence as horizontal bar, relative timestamp.

**Quick actions**: "Begin Surveillance" (navigates to Live Monitor + auto-starts webcam), "Register Suspect" (navigates to Criminal Form).

**Verify**: Counts match DB state, update after adding criminals. "Begin Surveillance" navigates and auto-starts. Monitoring status reflects actual webcam state.

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
