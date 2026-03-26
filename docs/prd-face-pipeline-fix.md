# PRD: Face Detection & Recognition Pipeline Overhaul

**Version**: 2.0
**Date**: March 26, 2026
**Status**: Implemented (v2 — with face alignment fix)

---

## Problem Statement

CrimDet's face recognition is fundamentally broken. When a user uploads a photo of **any** person, the system matches them against **every** criminal in the database at 95-98% confidence. The root cause: the "embedding" system is not a real face recognition model — it flattens a 64x64 grayscale thumbnail into a pixel array and calls it an embedding. Since all human faces have similar pixel distributions (dark at eyes, bright at forehead), cosine similarity between any two face images scores >0.90, making the 0.6 threshold useless.

Additionally, the Haar cascade face detector is oversensitive — a single-person photo triggers 9 face detections on shirt textures, shadows, and background objects (`minNeighbors=3`, `minSize=30x30`).

Both the detector and the recognizer must be replaced with real ML models.

---

## Solution

Replace the entire face processing pipeline:

1. **Face detection**: Replace Haar cascade with OpenCV's FaceDetectorYN (YuNet ONNX model, 233KB). Provides bounding boxes AND 5-point facial landmarks. DNN SSD and tightened Haar cascade as fallbacks.
2. **Face recognition**: Replace raw pixel extraction with SFace (OpenCV's `FaceRecognizerSF`, ~10MB int8 ONNX model), a real neural network that produces 128-dimensional identity-aware embeddings.
3. **Face alignment**: Use `FaceRecognizerSF.alignCrop()` with YuNet's 5-point landmarks to produce pose-invariant aligned face crops before embedding extraction. This is critical — without alignment, SFace produces inconsistent embeddings across different photos of the same person.
4. **Matching threshold**: Changed from 0.6 to 0.363 (SFace model author recommended cosine threshold).
5. **Database migration**: Auto re-enroll all criminals on startup when stale embeddings are detected.

All models bundled in `src/main/resources/models/` — zero-config, works offline. The fix applies to both the image scanner and the future live webcam pipeline since they share the same service layer.

---

## Implementation History

### Phase 1: Initial Fix (branch `ShikharJohari/face-pipeline-fix`, March 25, 2026)

Replaced the broken pipeline with real ML models:

**Commits:**
1. `603978e` — add DNN SSD face detector + SFace recognition model files
2. `66e39b4` — replace Haar-only face detection w/ DNN SSD primary + tightened Haar fallback
3. `d9b94ce` — fix: DNN-to-Haar runtime fallback, Mat leak on exception, Haar BGRA handling
4. `aa08137` — replace raw-pixel embedding w/ SFace neural net (128-d identity embeddings)
5. `11bbc89` — fix: thread safety, resource cleanup in FaceEmbeddingService
6. `3bd9ef2` — threshold 0.6 -> 0.363 (SFace model recommended cosine threshold)
7. `021c9fc` — auto re-enroll criminals on startup when embeddings are stale (wrong dim)
8. `da81dba` — update tests for DNN+SFace pipeline, add negative test case

**What changed:**
- `FaceDetectionService`: Haar cascade → DNN SSD primary detector (Caffe model, 300x300 input, 0.5 confidence threshold) with Haar fallback (tightened: minNeighbors=5, minSize=80x80)
- `FaceEmbeddingService`: Raw 64x64 pixel flattening → SFace ONNX neural net (128-D identity embeddings)
- `FaceMatchingService`: Threshold 0.6 → 0.363
- Startup migration: Dimension check triggers auto re-enrollment

**Result:** False matches eliminated. Same-photo matching works. But cross-photo matching (different photo of same person) failed — the system showed "Unknown" or matched the wrong person at ~38% confidence.

### Phase 2: Root Cause — Missing Face Alignment (March 26, 2026)

**Bug observed:** "Ankita Johari" enrolled with one photo. Scanning a *different* photo of Ankita matched "Yuvraj" at 38.9% instead of Ankita. Same photo always matched; different photos never did.

**Root cause investigation:**
The SFace model (`FaceRecognizerSF`) requires face alignment before embedding extraction. The correct OpenCV pipeline is:

1. `FaceDetectorYN.detect()` → returns face rows with 5 facial landmarks (right eye, left eye, nose tip, right/left mouth corners)
2. `FaceRecognizerSF.alignCrop(fullImage, faceRow)` → uses landmarks to compute a similarity transform (rotation + scale + translation) that warps the face onto canonical 112x112 positions
3. `FaceRecognizerSF.feature(alignedFace)` → produces the 128-D embedding

The Phase 1 code skipped steps 1-2 entirely. It used the DNN SSD detector (bounding boxes only, no landmarks), cropped the face, and passed the raw crop directly to `feature()`. Without alignment:
- Same photo → same crop → same embedding → match works
- Different photo → different crop (angle, pose, lighting) → inconsistent embedding → no match

This was confirmed by research: `alignCrop()` applies a Procrustes/similarity transform using SVD to map 5 source landmarks to hardcoded canonical destination coordinates on a 112x112 grid. Every official OpenCV sample calls `alignCrop()` before `feature()`. Google's research showed alignment improved FaceNet accuracy from 98.87% to 99.63% (3x fewer errors).

**Fix applied:**
1. Added `FaceDetectorYN` (YuNet) as primary face detector — provides 5-point landmarks needed by `alignCrop()`
2. Added `detectionRow` and `originalImage` fields to `DetectedFace` model to carry landmark data through the pipeline
3. `FaceEmbeddingService.extractEmbedding(DetectedFace)` now calls `alignCrop(originalImage, faceRow)` before `feature()` when landmarks are available, falls back to raw crop for detectors without landmarks
4. Force re-enrollment on startup to regenerate all embeddings with alignment
5. `LiveMonitorController` updated to pass full `DetectedFace` to embedding service

**Additional fixes during Phase 2:**
- `.gitignore`: `model/` → `/model/` (was accidentally ignoring `src/.../model/` directory)
- YuNet score threshold: 0.7 → 0.5 (0.7 was filtering out valid faces in high-res images)
- Image scaling for detection: Large images (>800px) scaled down for YuNet detection, coordinates mapped back to original resolution

### Files changed across both phases

| File | Phase 1 | Phase 2 |
|------|---------|---------|
| `FaceDetectionService.java` | DNN SSD + Haar fallback | + YuNet primary (landmarks), DNN SSD secondary, Haar tertiary; image scaling |
| `FaceEmbeddingService.java` | SFace neural net | + alignCrop before feature(); force re-enrollment |
| `FaceMatchingService.java` | Threshold 0.363 | (unchanged) |
| `DetectedFace.java` | (unchanged) | + detectionRow, originalImage fields |
| `LiveMonitorController.java` | (unchanged) | extractEmbedding(face) instead of extractEmbedding(crop) |
| `App.java` | + migrateEmbeddingsIfNeeded() | (unchanged) |
| `.gitignore` | (unchanged) | model/ → /model/ |
| `ScanPipelineTest.java` | Updated for SFace | (unchanged — uses BufferedImage overload) |

### Model files in `src/main/resources/models/`

| File | Size | Purpose |
|------|------|---------|
| `face_detection_yunet_2023mar.onnx` | 233KB | YuNet face detector (primary) — provides landmarks for alignment |
| `face_recognition_sface_2021dec_int8.onnx` | ~10MB | SFace face recognizer — produces 128-D identity embeddings |
| `res10_300x300_ssd_iter_140000.caffemodel` | ~10MB | DNN SSD face detector (secondary fallback) |
| `deploy.prototxt` | 28KB | DNN SSD architecture definition |
| `haarcascade_frontalface_default.xml` | 930KB | Haar cascade (tertiary fallback) |

---

## User Stories

1. As an operator, I want the scanner to only match people who actually look like enrolled criminals, so that I don't get false alerts on every scan.
2. As an operator, I want the face detector to only draw bounding boxes on real faces, so that I don't see phantom detections on shirt textures and background objects.
3. As an operator, I want confidence scores to be meaningful, so that a 95% match actually means strong visual similarity — not a pixel artifact.
4. As an operator, I want the system to work offline without downloading models on first run, so that demo day doesn't depend on WiFi.
5. As an operator, I want my existing criminal records to keep working after the update, so that I don't have to re-register everyone manually.
6. As an operator, I want face detection to work even if the DNN model fails to load, so that the system degrades gracefully instead of crashing.
7. As an operator, I want to upload a photo of a non-criminal and see zero matches, so that I can trust the system isn't crying wolf.
8. As an operator, I want to upload a photo of an enrolled criminal and see exactly one match with high confidence, so that I know the system is working.
9. As an operator, I want the scanner to handle tilted or partially-obscured faces reasonably, so that I don't need perfect passport photos for detection.
10. As an operator, I want the live webcam monitor to use the same improved detection and recognition, so that real-time surveillance is equally accurate.
11. As an operator, I want the app to start up quickly even when re-enrolling all criminals with the new model, so that I'm not waiting minutes on launch.
12. As a developer, I want the embedding dimension stored alongside the vector, so that future model upgrades trigger automatic re-enrollment.

---

## Final Implementation Decisions

### Face Detection (3-tier cascade)

1. **Primary — YuNet** (`FaceDetectorYN`): ONNX model, 233KB. Provides bounding boxes + 5-point facial landmarks. Score threshold: 0.5. Images >800px scaled down for detection, coordinates mapped back. Essential for face alignment.
2. **Secondary — DNN SSD**: Caffe model, ~10MB. Bounding boxes only (no landmarks, no alignment). Confidence threshold: 0.5. Used if YuNet fails to load.
3. **Tertiary — Haar cascade**: `minNeighbors=5`, `minSize=80x80`. No confidence scores, no landmarks. Used if both DNN models fail.

### Face Recognition (Embeddings)

- **Model**: SFace int8 ONNX (~10MB). Produces 128-dimensional float vectors.
- **Alignment**: `FaceRecognizerSF.alignCrop(originalImage, faceDetectionRow)` computes a similarity transform from 5 facial landmarks to canonical 112x112 positions. Applied before `feature()` when YuNet landmarks are available.
- **Fallback**: When no landmarks available (DNN SSD / Haar detection), passes raw face crop to `feature()` directly. Works but with reduced cross-photo accuracy.
- **API**: `extractEmbedding(DetectedFace)` for full pipeline with alignment; `extractEmbedding(BufferedImage)` for backward compat / tests.

### Face Matching

- **Similarity metric**: Cosine similarity (`EmbeddingUtils.cosineSimilarity()`).
- **Threshold**: 0.363 (SFace model author recommended, from `opencv_zoo/models/face_recognition_sface/sface.py`).

### Database Migration

- **Strategy**: On startup, force re-enrollment of all criminals (embeddings generated without alignment are invalid even if dimension matches).
- **Re-enrollment**: Iterates all criminals, calls `enrollCriminal(id)` for each. Deletes old embeddings and regenerates from stored photos.
- **Note**: The `forceReenroll = true` flag in `migrateEmbeddingsIfNeeded()` should be set to `false` once all installations have re-enrolled with the alignment fix.

---

## Testing Decisions

### What makes a good test

Tests should verify external behavior, not implementation details. A face recognition test should assert "same person matches, different person doesn't" — not "the embedding vector has these specific float values." Tests should be deterministic and not depend on exact model outputs beyond directional correctness.

### Modules to test

1. **FaceDetectionService** — Given an image with a known number of faces, assert correct face count. Given an image with no faces, assert empty result. Assert YuNet detector is used by default, fallbacks work.

2. **FaceEmbeddingService** — Given the same face image twice, assert cosine similarity > 0.9 (near-identical). Given two clearly different faces, assert cosine similarity < 0.363. Assert embedding dimension is 128.

3. **FaceMatchingService** — End-to-end: enroll a criminal with photo, scan same photo → match found. Enroll a criminal, scan a different person → no match. Assert threshold behavior at boundary.

4. **ScanPipelineTest** (existing) — Full pipeline from registration through scan. Includes negative test: register person A, scan person B, assert zero matches. Uses synthetic images with `extractEmbedding(BufferedImage)` overload since synthetic faces may not trigger YuNet/DNN detectors.

### Prior art

`ScanPipelineTest.java` tests register → enroll → scan → match with synthetic face images (drawn ovals). Uses `extractEmbedding(BufferedImage)` to bypass the detector (synthetic faces don't trigger DNN/YuNet). Tests self-similarity, cross-image dissimilarity, and full pipeline matching.

---

## Out of Scope

- **GPU acceleration**: All inference is CPU-only. SFace is fast enough (~10ms per face on CPU).
- **Configurable threshold UI**: The threshold is hardcoded at 0.363. A UI slider can be added separately.
- **Model versioning in DB schema**: Adding a `model_version` column to `face_embeddings` would be more robust, but force-reenroll is sufficient for this project's scale.
- **Multi-model ensemble**: Using multiple recognition models and averaging scores. Overkill for a college project.
- **WebcamService implementation**: The live webcam capture service is described in the original PRD/plan (Phase 7). This PRD fixes the face pipeline that both scanner and webcam will use, but doesn't implement the webcam service itself.

---

## Key Lessons Learned

### Face alignment is not optional

The original PRD (v1.0) listed face alignment as "out of scope — can be added later as a polish step." This was wrong. Face alignment is **required** for SFace (and most modern face recognition models). Without it, the model produces embeddings that are inconsistent across different photos of the same person, making cross-photo matching impossible.

The SFace model was trained on faces aligned to specific canonical positions using 5-point landmarks (eyes, nose, mouth corners). Feeding it unaligned crops means the CNN's spatial feature maps don't correspond to expected facial regions. Google's research showed alignment improved FaceNet accuracy from 98.87% to 99.63% — a 3x reduction in error rate.

### Detector and recognizer must be paired correctly

The DNN SSD face detector produces bounding boxes but no facial landmarks. SFace needs landmarks for `alignCrop()`. Using DNN SSD + SFace without alignment produced the worst outcome: embeddings that appeared correct (128-D, L2-normalized) but were semantically meaningless for cross-photo matching.

The correct pairing: `FaceDetectorYN` (provides landmarks) → `FaceRecognizerSF.alignCrop()` → `FaceRecognizerSF.feature()`. This is the pipeline documented in every official OpenCV sample.

### YuNet detection threshold and image scaling

YuNet's default score threshold (0.9) and the initial setting (0.7) were too strict for real-world photos, especially high-resolution images. Lowering to 0.5 and adding image scaling (resize >800px images for detection, map coordinates back) fixed detection reliability.

---

## Further Notes

### Why SFace over MobileFaceNet (original PRD spec)

The original PRD v1.0 specified MobileFaceNet. We chose SFace because:
- It's integrated into OpenCV as `FaceRecognizerSF` with built-in `alignCrop()` — no separate alignment implementation needed
- The int8 quantized version (~10MB) is fast and compact
- It pairs naturally with `FaceDetectorYN` (YuNet) — both are OpenCV's recommended face pipeline
- The 0.363 cosine threshold is well-documented in the opencv_zoo

### Model sourcing

- **YuNet face detector**: `face_detection_yunet_2023mar.onnx` from [opencv_zoo](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet) or [HuggingFace](https://huggingface.co/opencv/face_detection_yunet)
- **SFace recognizer**: `face_recognition_sface_2021dec_int8.onnx` from [opencv_zoo](https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface)
- **DNN SSD detector**: `res10_300x300_ssd_iter_140000.caffemodel` from OpenCV's `samples/dnn/face_detector/`

### Relationship to existing docs

- `docs/prd.md` — Original v1 vision document. Aspirational. References RetinaFace + ArcFace.
- `docs/implementation-plan.md` — Original 11-phase build plan. Phases 4-5 (Face Detection + Embeddings) are now properly implemented via this PRD.
- **This document** — The complete implementation record for the face pipeline overhaul, including both initial fix and alignment correction.
