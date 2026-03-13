# Product Requirements Document (PRD)
# CrimDet — Criminal Face Detection System

**Version**: 1.0
**Date**: March 13, 2026
**Author**: Shikhar Johari
**Status**: Draft

---

## 1. Executive Summary

CrimDet is a desktop-based criminal face detection system built in Java that uses machine learning to identify criminal suspects in real-time through webcam surveillance. The system maintains a database of criminal records with facial embeddings and continuously compares live video feed faces against the database, alerting the operator when a potential match is detected.

The application combines computer vision (face detection), deep learning (face embedding extraction), and database matching (cosine similarity) into a unified, polished desktop application with a modern dark-themed security dashboard interface.

---

## 2. Problem Statement

Law enforcement and security agencies need efficient tools to identify known criminal suspects from live surveillance feeds. Manual monitoring of video feeds is:

- **Error-prone**: Human operators miss faces, especially during long shifts
- **Slow**: Manual comparison against paper or digital mugshot databases is time-consuming
- **Unscalable**: A single operator can only monitor a limited number of faces

An automated face detection and matching system can continuously process video feeds, compare against a criminal database in milliseconds, and alert operators only when a potential match is found — dramatically improving detection rates and response times.

---

## 3. Goals and Objectives

### Primary Goals
1. **Real-time face detection** from live webcam feeds with visual bounding box overlays
2. **Automated face matching** against a criminal database using neural network face embeddings
3. **Instant alerts** when a criminal match is detected above a configurable confidence threshold
4. **Criminal record management** with face enrollment (photo upload + webcam capture)
5. **Detection logging and history** for audit trails and reporting

### Success Criteria
- Face detection processes at least 10 frames per second on consumer hardware (CPU-only)
- Face matching accuracy: same-person cosine similarity > 0.6, different-person < 0.4
- End-to-end detection-to-alert latency under 500ms
- Application launches and is fully operational within 30 seconds (after initial model download)
- Zero-configuration deployment — no external database servers or services required

### Non-Goals
- Multi-camera support (single webcam input only)
- Network/remote camera feeds (RTSP, IP cameras)
- User authentication or role-based access control (single admin operator)
- Mobile or web deployment
- Training custom ML models (uses pre-trained models only)
- Real-time video recording or playback

---

## 4. Target Users

### Primary User: Security Operator / Administrator
- **Profile**: A single operator who manages the criminal database and monitors the live feed
- **Technical skill**: Basic computer literacy, no programming knowledge required
- **Environment**: Desktop workstation with webcam, connected to local surveillance setup
- **Tasks**:
  - Add/edit/remove criminal records with facial photos
  - Monitor live webcam feed for criminal matches
  - Review detection history and generate reports
  - Adjust system settings (confidence threshold)

---

## 5. Functional Requirements

### 5.1 Criminal Database Management

| ID | Requirement | Priority |
|---|---|---|
| FR-1.1 | System shall allow adding criminal records with: name, crime type, description, status | Must Have |
| FR-1.2 | System shall support uploading multiple facial photos per criminal (JPEG/PNG) | Must Have |
| FR-1.3 | System shall support capturing facial photos directly from the webcam | Must Have |
| FR-1.4 | System shall automatically detect and validate faces in uploaded/captured photos | Must Have |
| FR-1.5 | System shall reject photos containing zero or multiple faces with a clear error message | Must Have |
| FR-1.6 | System shall automatically extract and store facial embeddings upon photo save | Must Have |
| FR-1.7 | System shall allow editing existing criminal records and photos | Must Have |
| FR-1.8 | System shall allow deleting criminal records with confirmation dialog | Must Have |
| FR-1.9 | System shall support searching criminals by name (live filtering) | Must Have |
| FR-1.10 | System shall support filtering criminals by status (Wanted/Arrested/Released) | Must Have |
| FR-1.11 | System shall support filtering criminals by crime type | Should Have |
| FR-1.12 | Criminal status options: WANTED, ARRESTED, RELEASED | Must Have |

### 5.2 Face Detection and Recognition

| ID | Requirement | Priority |
|---|---|---|
| FR-2.1 | System shall detect faces in video frames using RetinaFace neural network model | Must Have |
| FR-2.2 | System shall extract 512-dimensional face embedding vectors using ArcFace model | Must Have |
| FR-2.3 | System shall process at least every 3rd video frame for face detection | Must Have |
| FR-2.4 | System shall display all video frames for smooth visual feed (even unprocessed ones) | Must Have |
| FR-2.5 | System shall compare detected face embeddings against all stored criminal embeddings | Must Have |
| FR-2.6 | System shall use cosine similarity for face matching | Must Have |
| FR-2.7 | Default match threshold shall be 0.6 (configurable via UI slider) | Must Have |
| FR-2.8 | System shall cache all criminal embeddings in memory for fast matching | Must Have |
| FR-2.9 | System shall refresh the embedding cache when criminals are added/modified/deleted | Must Have |

### 5.3 Live Monitoring

| ID | Requirement | Priority |
|---|---|---|
| FR-3.1 | System shall capture and display live webcam feed at ~30 FPS | Must Have |
| FR-3.2 | System shall draw green bounding boxes around unrecognized faces | Must Have |
| FR-3.3 | System shall draw red bounding boxes around matched criminal faces | Must Have |
| FR-3.4 | Matched face bounding boxes shall display: criminal name + confidence percentage | Must Have |
| FR-3.5 | System shall play an audio alert when a new criminal match is detected | Must Have |
| FR-3.6 | System shall debounce alerts for the same criminal (30-second cooldown) | Must Have |
| FR-3.7 | System shall display a real-time alert feed panel showing recent matches | Must Have |
| FR-3.8 | Alert feed entries shall show: criminal photo, name, confidence, timestamp | Must Have |
| FR-3.9 | System shall provide start/stop controls for the monitoring session | Must Have |
| FR-3.10 | System shall provide an adjustable confidence threshold slider (0.4 to 0.9) | Must Have |
| FR-3.11 | System shall display current FPS counter | Should Have |
| FR-3.12 | System shall automatically log all matches to the detection_logs database table | Must Have |
| FR-3.13 | System shall capture and store a screenshot at the time of each detection | Must Have |

### 5.4 Dashboard

| ID | Requirement | Priority |
|---|---|---|
| FR-4.1 | Dashboard shall display total number of criminals in the database | Must Have |
| FR-4.2 | Dashboard shall display count of currently wanted criminals | Must Have |
| FR-4.3 | Dashboard shall display total detections logged today | Must Have |
| FR-4.4 | Dashboard shall display a list of the 10 most recent detection alerts | Must Have |
| FR-4.5 | Recent alerts shall show: criminal thumbnail, name, confidence, time | Must Have |
| FR-4.6 | Dashboard shall provide quick-action buttons: "Start Monitoring", "Add Criminal" | Must Have |
| FR-4.7 | Dashboard stats shall refresh when navigated to | Should Have |

### 5.5 Detection History

| ID | Requirement | Priority |
|---|---|---|
| FR-5.1 | System shall display all detection logs in a chronological table | Must Have |
| FR-5.2 | Table columns: screenshot thumbnail, criminal name, confidence, timestamp, notes | Must Have |
| FR-5.3 | System shall support filtering by date range | Must Have |
| FR-5.4 | System shall support filtering by minimum confidence score | Should Have |
| FR-5.5 | System shall support filtering by criminal name | Should Have |
| FR-5.6 | Clicking a row shall show the full screenshot and detection details in a dialog | Must Have |
| FR-5.7 | System shall support exporting filtered detection logs to CSV | Must Have |
| FR-5.8 | System shall support pagination for large result sets (>100 records) | Should Have |

---

## 6. Non-Functional Requirements

### 6.1 Performance

| ID | Requirement |
|---|---|
| NFR-1.1 | Application startup time: < 30 seconds (after initial model download) |
| NFR-1.2 | Face detection latency: < 200ms per frame on modern CPU |
| NFR-1.3 | Face matching latency: < 10ms for up to 1,000 enrolled criminals |
| NFR-1.4 | Video feed display: smooth 30 FPS rendering |
| NFR-1.5 | Memory usage: < 2GB RAM under normal operation |
| NFR-1.6 | ML model inference must run off the UI thread to prevent freezing |

### 6.2 Reliability

| ID | Requirement |
|---|---|
| NFR-2.1 | Application shall handle webcam disconnection gracefully (error message, no crash) |
| NFR-2.2 | Application shall handle ML model loading failures with user-friendly error dialogs |
| NFR-2.3 | Database corruption shall not cause data loss (H2's built-in recovery) |
| NFR-2.4 | Application shall shut down gracefully: stop webcam, release models, close DB connection pool |
| NFR-2.5 | Global uncaught exception handler shall display error dialog instead of silent crash |

### 6.3 Usability

| ID | Requirement |
|---|---|
| NFR-3.1 | Dark-themed security dashboard aesthetic (AtlantaFX Nord Dark) |
| NFR-3.2 | Sidebar navigation with Material Design icons for all 5 screens |
| NFR-3.3 | Consistent visual language: stat cards, tables, forms use uniform styling |
| NFR-3.4 | Clear visual distinction between matched (red) and unmatched (green) faces |
| NFR-3.5 | All destructive actions (delete criminal) require confirmation dialog |
| NFR-3.6 | Form validation with clear error messages (e.g., "No face detected in photo") |
| NFR-3.7 | Splash screen with progress indicator during model loading |

### 6.4 Deployment

| ID | Requirement |
|---|---|
| NFR-4.1 | Zero external dependencies — no database server, no cloud services |
| NFR-4.2 | Single `./gradlew run` command to launch (after Java 17+ is installed) |
| NFR-4.3 | H2 database auto-creates on first run, data persists in `./data/` directory |
| NFR-4.4 | ML models auto-download on first run to `~/.djl.ai/cache/` |
| NFR-4.5 | Compatible with macOS, Linux, and Windows |

---

## 7. System Architecture

### 7.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    JavaFX UI Layer                       │
│  ┌───────────┐ ┌──────────┐ ┌────────┐ ┌────────────┐  │
│  │ Dashboard  │ │  Live    │ │Criminal│ │  Detection  │  │
│  │ Controller │ │ Monitor  │ │  CRUD  │ │  History    │  │
│  └─────┬─────┘ └────┬─────┘ └───┬────┘ └─────┬──────┘  │
│        │             │           │             │         │
├────────┴─────────────┴───────────┴─────────────┴────────┤
│                    Service Layer                         │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────┐  │
│  │ Criminal  │ │  Face    │ │  Face    │ │  Webcam   │  │
│  │ Service   │ │Detection │ │Matching  │ │  Service  │  │
│  └─────┬────┘ │ Service  │ │ Service  │ └─────┬─────┘  │
│        │      └─────┬────┘ └─────┬────┘       │        │
│        │            │            │             │        │
│  ┌─────┴────────────┴────────────┴─────────────┘        │
│  │              Shared Components                       │
│  │  ┌──────────┐  ┌────────────┐  ┌──────────────┐     │
│  │  │Embedding │  │   Alert    │  │    Stats     │     │
│  │  │ Service  │  │  Service   │  │   Service    │     │
│  │  └──────────┘  └────────────┘  └──────────────┘     │
├─────────────────────────────────────────────────────────┤
│                   Repository Layer                       │
│  ┌──────────┐ ┌────────────┐ ┌──────────┐ ┌─────────┐  │
│  │Criminal  │ │  Criminal  │ │   Face   │ │Detection│  │
│  │  Repo    │ │ Photo Repo │ │Embed Repo│ │ Log Repo│  │
│  └─────┬────┘ └──────┬─────┘ └────┬─────┘ └────┬────┘  │
├────────┴─────────────┴────────────┴─────────────┴───────┤
│               H2 Embedded Database                       │
│  ┌──────────┐ ┌────────────────┐ ┌──────────────────┐   │
│  │criminals │ │criminal_photos │ │ face_embeddings  │   │
│  └──────────┘ └────────────────┘ └──────────────────┘   │
│  ┌──────────────────┐                                    │
│  │  detection_logs  │                                    │
│  └──────────────────┘                                    │
└─────────────────────────────────────────────────────────┘
```

### 7.2 Face Processing Pipeline

```
┌──────────┐    ┌───────────────┐    ┌────────────────┐    ┌─────────────┐    ┌─────────┐
│  Webcam  │───>│ Face Detection│───>│   Embedding    │───>│   Matching  │───>│  Alert  │
│  Capture │    │  (RetinaFace) │    │  (ArcFace)     │    │  (Cosine)   │    │ Service │
│  30 FPS  │    │  Every 3rd    │    │  float[512]    │    │  >= 0.6     │    │         │
└──────────┘    │  frame        │    │  per face      │    │  threshold  │    │         │
                └───────────────┘    └────────────────┘    └─────────────┘    └─────────┘
     │                                                                              │
     │              ┌───────────────────────────────────────────────────┐           │
     └─────────────>│              JavaFX UI Thread                     │<──────────┘
                    │  - Display video frames (ImageView)              │
                    │  - Draw bounding boxes (Canvas overlay)          │
                    │  - Show alerts (ListView)                        │
                    │  - Play alert sound (AudioClip)                  │
                    └───────────────────────────────────────────────────┘
```

### 7.3 Threading Model

| Thread | Responsibility |
|---|---|
| JavaFX Application Thread | UI rendering, event handling, bounding box drawing |
| Webcam Capture Thread | Grabs frames from camera at 30 FPS, stores in AtomicReference |
| ML Processing Thread | Face detection + embedding extraction + matching (ScheduledExecutorService) |
| Alert Thread | Sound playback (short-lived, from JavaFX AudioClip) |

**Thread safety**: Webcam frames passed via `AtomicReference<BufferedImage>`. UI updates via `Platform.runLater()`. Embedding cache uses `ConcurrentHashMap` or synchronized reads.

---

## 8. Data Model

### 8.1 Entity-Relationship Diagram

```
┌─────────────────┐       ┌──────────────────┐       ┌──────────────────┐
│    criminals     │       │ criminal_photos  │       │ face_embeddings  │
├─────────────────┤       ├──────────────────┤       ├──────────────────┤
│ id (PK)         │──┐    │ id (PK)          │──┐    │ id (PK)          │
│ name            │  │    │ criminal_id (FK) │  │    │ criminal_id (FK) │
│ crime_type      │  ├───>│ photo_data (BLOB)│  ├───>│ photo_id (FK)    │
│ description     │  │    │ created_at       │  │    │ embedding (BLOB) │
│ status          │  │    └──────────────────┘  │    │ created_at       │
│ created_at      │  │                          │    └──────────────────┘
│ updated_at      │  │    ┌──────────────────┐  │
└─────────────────┘  │    │  detection_logs  │  │
                     │    ├──────────────────┤  │
                     │    │ id (PK)          │  │
                     └───>│ criminal_id (FK) │  │
                          │ confidence       │
                          │ screenshot (BLOB)│
                          │ detected_at      │
                          │ notes            │
                          └──────────────────┘
```

### 8.2 Table Specifications

**criminals**
| Column | Type | Constraints | Description |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | Unique identifier |
| name | VARCHAR(255) | NOT NULL | Criminal's full name |
| crime_type | VARCHAR(100) | NOT NULL | Category (Theft, Assault, Fraud, etc.) |
| description | TEXT | | Details about the criminal/crime |
| status | VARCHAR(20) | NOT NULL, DEFAULT 'WANTED' | WANTED, ARRESTED, or RELEASED |
| created_at | TIMESTAMP | NOT NULL, DEFAULT CURRENT_TIMESTAMP | Record creation time |
| updated_at | TIMESTAMP | NOT NULL, DEFAULT CURRENT_TIMESTAMP | Last modification time |

**criminal_photos**
| Column | Type | Constraints | Description |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | Unique identifier |
| criminal_id | BIGINT | FK → criminals(id), ON DELETE CASCADE | Associated criminal |
| photo_data | BLOB | NOT NULL | JPEG-encoded photo bytes |
| created_at | TIMESTAMP | NOT NULL, DEFAULT CURRENT_TIMESTAMP | Upload time |

**face_embeddings**
| Column | Type | Constraints | Description |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | Unique identifier |
| criminal_id | BIGINT | FK → criminals(id), ON DELETE CASCADE | Associated criminal |
| photo_id | BIGINT | FK → criminal_photos(id), ON DELETE CASCADE | Source photo |
| embedding | BLOB | NOT NULL | Serialized float[512] (2048 bytes) |
| created_at | TIMESTAMP | NOT NULL, DEFAULT CURRENT_TIMESTAMP | Extraction time |

**detection_logs**
| Column | Type | Constraints | Description |
|---|---|---|---|
| id | BIGINT | PK, AUTO_INCREMENT | Unique identifier |
| criminal_id | BIGINT | FK → criminals(id) | Matched criminal |
| confidence | DOUBLE | NOT NULL | Cosine similarity score (0.0 to 1.0) |
| screenshot | BLOB | | Frame screenshot at time of detection |
| detected_at | TIMESTAMP | NOT NULL, DEFAULT CURRENT_TIMESTAMP | Detection time |
| notes | VARCHAR(500) | | Optional operator notes |

---

## 9. User Interface Specifications

### 9.1 Visual Design System

**Theme**: AtlantaFX Nord Dark — a dark, muted color palette suited for security/surveillance applications
- Background: Dark gray (#2E3440)
- Surface/Cards: Slightly lighter (#3B4252)
- Accent: Frost blue (#88C0D0) for active states and highlights
- Danger: Aurora red (#BF616A) for criminal match alerts
- Success: Aurora green (#A3BE8C) for unknown/safe faces
- Text: Snow white (#ECEFF4)

**Typography**: System default (AtlantaFX handles this), monospace for data values

**Icons**: Ikonli Material Design 2 icon pack — consistent, recognizable icon set

**Card Component**: Rounded corners (8px radius), subtle drop shadow, consistent padding (16px)

### 9.2 Screen Layouts

#### 9.2.1 Application Shell
```
┌──────┬──────────────────────────────────────────┐
│      │                                          │
│  D   │                                          │
│  A   │                                          │
│  S   │                                          │
│  H   │          Content Area                    │
│      │          (StackPane — loads               │
│  M   │           child FXML based               │
│  O   │           on sidebar selection)           │
│  N   │                                          │
│      │                                          │
│  R   │                                          │
│  E   │                                          │
│  C   │                                          │
│      │                                          │
│  H   │                                          │
│  I   │                                          │
│  S   │                                          │
│      │                                          │
└──────┴──────────────────────────────────────────┘
 Sidebar    ~80% width content area
 ~80px
```

#### 9.2.2 Dashboard
```
┌────────────────────────────────────────────────────┐
│  [Total Criminals]  [Wanted]  [Detections Today]   │
│       42              18            7              │
├────────────────────────────────────────────────────┤
│                                                    │
│  Recent Alerts                                     │
│  ┌──────────────────────────────────────────────┐  │
│  │ [Photo] John Doe  |  87.3%  |  2:14 PM      │  │
│  │ [Photo] Jane Smith|  72.1%  |  1:58 PM      │  │
│  │ [Photo] Bob Wilson|  65.4%  |  12:30 PM     │  │
│  └──────────────────────────────────────────────┘  │
│                                                    │
│  [Start Monitoring]  [Add Criminal]                │
└────────────────────────────────────────────────────┘
```

#### 9.2.3 Live Monitor
```
┌──────────────────────────────────┬─────────────────┐
│  [Start/Stop]  Threshold: [===] │  FPS: 28        │
├──────────────────────────────────┼─────────────────┤
│                                  │  Alert Feed     │
│                                  │                 │
│    ┌─────────────┐               │  ┌───────────┐  │
│    │  ┌───┐      │               │  │[Img] John │  │
│    │  │   │ GREEN │               │  │ 87% 2:14  │  │
│    │  └───┘      │               │  └───────────┘  │
│    │       ┌───┐ │               │                 │
│    │       │   │RED: "John 87%"  │  ┌───────────┐  │
│    │       └───┘ │               │  │[Img] Jane │  │
│    │             │               │  │ 72% 1:58  │  │
│    └─────────────┘               │  └───────────┘  │
│     Webcam Feed + Canvas         │                 │
│                                  │                 │
└──────────────────────────────────┴─────────────────┘
         ~70% width                    ~30% width
```

#### 9.2.4 Criminal Records List
```
┌────────────────────────────────────────────────────┐
│  Search: [___________]  Status: [All ▼]  [+ Add]  │
├────────────────────────────────────────────────────┤
│  Photo │ Name        │ Crime Type │ Status │ Date  │
│  ──────┼─────────────┼────────────┼────────┼────── │
│  [img] │ John Doe    │ Theft      │ WANTED │ Mar 1 │
│  [img] │ Jane Smith  │ Fraud      │ ARRESTED│ Feb  │
│  [img] │ Bob Wilson  │ Assault    │ WANTED │ Jan   │
│  ...   │             │            │        │       │
└────────────────────────────────────────────────────┘
```

#### 9.2.5 Criminal Form (Add/Edit)
```
┌────────────────────────────────────────────────────┐
│  Add New Criminal                                  │
├────────────────────────────────────────────────────┤
│                                                    │
│  Name:       [_________________________]           │
│  Crime Type: [Theft              ▼]                │
│  Status:     (●) Wanted  (○) Arrested  (○) Released│
│  Description:[________________________]            │
│              [________________________]            │
│                                                    │
│  Photos:                                           │
│  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────────────────┐  │
│  │[img] │ │[img] │ │  +   │ │ Capture Webcam  │  │
│  │  ✕   │ │  ✕   │ │Upload│ │     [Camera]     │  │
│  └──────┘ └──────┘ └──────┘ └──────────────────┘  │
│                                                    │
│  [Save]  [Cancel]                                  │
└────────────────────────────────────────────────────┘
```

#### 9.2.6 Detection History
```
┌────────────────────────────────────────────────────┐
│  From: [03/01/2026]  To: [03/13/2026]  [Export CSV]│
│  Min Confidence: [====60%===]                      │
├────────────────────────────────────────────────────┤
│  Screenshot│ Criminal    │ Confidence│ Detected At │
│  ──────────┼─────────────┼───────────┼──────────── │
│  [img]     │ John Doe    │  87.3%    │ Mar 13 2:14 │
│  [img]     │ Jane Smith  │  72.1%    │ Mar 13 1:58 │
│  [img]     │ Bob Wilson  │  65.4%    │ Mar 13 12:30│
│  ...       │             │           │             │
├────────────────────────────────────────────────────┤
│  Page 1 of 3    [<] [1] [2] [3] [>]               │
└────────────────────────────────────────────────────┘
```

---

## 10. Technology Stack Deep Dive

### 10.1 Deep Java Library (DJL)

**What**: Open-source, Amazon-developed Java framework for deep learning inference. Abstracts away the complexity of loading and running neural network models.

**Why DJL over raw ONNX Runtime**:
- Clean Java API — model loading in ~5 lines of code
- Automatic model download and caching
- Automatic native library management (CPU/GPU)
- Cross-platform (macOS, Linux, Windows)
- Pre-built model zoo with face detection and recognition models

**Models used**:
| Task | Model | Architecture | Input | Output |
|---|---|---|---|---|
| Face Detection | RetinaFace | ResNet-50 backbone | Image (any size) | List of bounding boxes + probabilities |
| Face Embedding | ArcFace | ResNet-100 backbone | Cropped face (112x112) | float[512] embedding vector |

**Fallback strategy**: If DJL model zoo doesn't contain the exact models, use PaddlePaddle engine (proven in open-source Java face recognition projects) or load ONNX models directly.

### 10.2 Face Matching Algorithm

**Method**: Cosine Similarity

```
similarity(A, B) = (A · B) / (||A|| × ||B||)
```

Where A and B are 512-dimensional face embedding vectors.

**Interpretation**:
| Score | Meaning |
|---|---|
| 0.0 – 0.3 | Definitely different people |
| 0.3 – 0.5 | Likely different people |
| 0.5 – 0.6 | Uncertain — borderline |
| 0.6 – 0.75 | Likely same person (default threshold: 0.6) |
| 0.75 – 1.0 | High confidence same person |

**Performance**: Brute-force comparison of float[512] vectors. For N criminals with M embeddings each:
- Time complexity: O(N × M) per query face
- At 1,000 criminals × 3 photos each = 3,000 comparisons × 512 multiplications = ~1.5M floating point ops
- This completes in < 1ms on modern CPUs — no need for approximate nearest neighbor algorithms

### 10.3 JavaFX + AtlantaFX

**JavaFX**: Modern Java GUI toolkit. Supports FXML (XML-based layout), CSS styling, hardware-accelerated rendering, media playback, and the WebView component.

**AtlantaFX**: Theme library providing pre-built dark and light themes modeled after modern design systems. NordDark theme chosen for its muted, professional appearance suited to security/surveillance UX.

**Ikonli**: Icon packs for JavaFX. Material Design 2 icon pack provides 7,000+ icons for consistent, modern iconography.

### 10.4 H2 Embedded Database

**Why H2 over MySQL/PostgreSQL**:
- Zero installation — pure Java, runs in-process
- Auto-creates database file on first access
- Full SQL support, JDBC compatible
- Built-in web console for debugging (accessible at `localhost:8082` during development)
- File-based persistence in `./data/crimdet.mv.db`

**Why H2 over SQLite**:
- Pure Java (SQLite requires native libraries via JNI)
- Better Java ecosystem integration
- Built-in connection pooling support

### 10.5 JDBI

**Why JDBI over Hibernate/JPA**:
- SQL-first — write real SQL queries, no HQL/JPQL abstraction
- Lightweight — no entity scanning, no lazy loading, no session management
- Declarative: `@SqlQuery` / `@SqlUpdate` annotations on interfaces
- Feels natural to developers coming from Go's `database/sql` pattern
- Much simpler debugging — the SQL you write is the SQL that runs

---

## 11. User Workflows

### 11.1 First Launch
1. User runs `./gradlew run`
2. Splash screen appears showing "Downloading ML models..." with progress bar (first time only, ~100MB)
3. Schema auto-created in H2
4. Dashboard loads with empty stats (0 criminals, 0 detections)
5. User clicks "Add Criminal" to begin populating the database

### 11.2 Enrolling a Criminal
1. User navigates to Criminal Records → clicks "Add Criminal"
2. Fills in: Name ("John Doe"), Crime Type (selects "Theft"), Description, Status ("Wanted")
3. Uploads 2-3 facial photos OR clicks "Capture from Webcam" to snap live photos
4. System validates each photo: detects exactly 1 face, shows green check or error
5. User clicks "Save"
6. System extracts face embeddings from each validated photo
7. Criminal record + photos + embeddings stored in database
8. User redirected to Criminal Records list, new entry visible

### 11.3 Monitoring Live Feed
1. User navigates to Live Monitor
2. Clicks "Start" — webcam activates, live feed appears
3. Faces in the feed get green bounding boxes (unrecognized)
4. User adjusts confidence threshold slider if needed (default 0.6)
5. When a face matches a criminal: bounding box turns red, name + confidence shown
6. Alert sound plays, entry added to right-side alert feed panel
7. Match automatically logged to detection_logs with screenshot
8. User can click "View Record" on an alert to see the full criminal profile
9. User clicks "Stop" to end monitoring session

### 11.4 Reviewing Detection History
1. User navigates to Detection History
2. Sees chronological list of all past matches
3. Filters by date range (e.g., "last 7 days") or minimum confidence
4. Clicks a row to see full screenshot and match details
5. Clicks "Export CSV" to save filtered results for reporting

---

## 12. Technical Constraints and Assumptions

### Constraints
1. **CPU-only inference**: No GPU requirement. All ML models run on CPU for maximum compatibility
2. **Single webcam**: Only one camera input supported at a time
3. **Local operation**: No network connectivity required after initial model download
4. **Java 17+**: Minimum JDK version for modern language features and JavaFX compatibility
5. **Desktop only**: No mobile or web access

### Assumptions
1. The workstation has a functional webcam with OS-level camera permissions granted
2. Java 17+ JDK is installed on the target machine
3. Internet connection is available for the first launch (model download)
4. The criminal database will contain fewer than 10,000 records (brute-force matching is sufficient)
5. Webcam resolution is at least 640x480

---

## 13. Glossary

| Term | Definition |
|---|---|
| **Face Detection** | Locating faces within an image/video frame and returning their bounding box coordinates |
| **Face Recognition/Matching** | Determining whether a detected face matches a known identity |
| **Face Embedding** | A compact numerical representation (float[512] vector) of a face that captures its unique features |
| **Cosine Similarity** | Mathematical measure of similarity between two vectors, ranging from -1 (opposite) to 1 (identical) |
| **Bounding Box** | A rectangle drawn around a detected face, defined by (x, y, width, height) |
| **RetinaFace** | A state-of-the-art face detection neural network that provides accurate face localization |
| **ArcFace** | A face recognition model that produces discriminative face embeddings for identity comparison |
| **DJL** | Deep Java Library — Amazon's open-source framework for machine learning in Java |
| **JavaCV** | Java bindings for OpenCV and FFmpeg, used for webcam capture and image processing |
| **JDBI** | A SQL convenience library for Java that provides a cleaner API over raw JDBC |
| **H2** | A lightweight, embedded SQL database written in pure Java |
| **AtlantaFX** | A modern theme library for JavaFX applications |
| **BLOB** | Binary Large Object — database column type for storing binary data like images |

---

## 14. Appendix: Dependency Coordinates

```kotlin
// build.gradle.kts
val djlVersion = "0.30.0"

dependencies {
    // DJL - Deep Learning
    implementation(platform("ai.djl:bom:$djlVersion"))
    implementation("ai.djl:api")
    implementation("ai.djl:model-zoo")
    implementation("ai.djl.pytorch:pytorch-engine")
    runtimeOnly("ai.djl.pytorch:pytorch-native-auto")

    // JavaCV - Webcam Capture
    implementation("org.bytedeco:javacv-platform:1.5.10")

    // Database
    implementation("com.h2database:h2:2.2.224")
    implementation("org.jdbi:jdbi3-core:3.43.0")
    implementation("org.jdbi:jdbi3-sqlobject:3.43.0")
    implementation("com.zaxxer:HikariCP:5.1.0")

    // UI
    implementation("io.github.mkpaz:atlantafx-base:2.0.1")
    implementation(platform("org.kordamp.ikonli:ikonli-bom:12.3.1"))
    implementation("org.kordamp.ikonli:ikonli-javafx")
    implementation("org.kordamp.ikonli:ikonli-materialdesign2-pack")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
```
