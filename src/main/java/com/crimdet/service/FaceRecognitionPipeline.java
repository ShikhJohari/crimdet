package com.crimdet.service;

import com.crimdet.model.DetectedFace;
import com.crimdet.model.Embedding;
import com.crimdet.model.MatchResult;
import com.crimdet.model.RecognizedFace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Single entry point for the detect → embed → match flow and for enrollment.
 *
 * Hides the three underlying services and the shape of their collaboration:
 * the full source frame never escapes this class, so callers can't leak it
 * by forgetting a null-out. Controllers depend only on this type plus
 * {@link RecognizedFace} / {@link MatchResult}; they are otherwise blind to
 * detection fallbacks, alignment, and cache wiring.
 *
 * Thread-safe via the underlying services' own locks.
 */
public final class FaceRecognitionPipeline {

    private static final Logger log = LoggerFactory.getLogger(FaceRecognitionPipeline.class);

    private static volatile FaceRecognitionPipeline instance;

    private final FaceDetectionService detectionService;
    private final FaceEmbeddingService embeddingService;
    private final FaceMatchingService matchingService;

    public static FaceRecognitionPipeline getInstance() {
        FaceRecognitionPipeline local = instance;
        if (local == null) {
            synchronized (FaceRecognitionPipeline.class) {
                local = instance;
                if (local == null) {
                    local = new FaceRecognitionPipeline(
                            FaceDetectionService.getInstance(),
                            new FaceEmbeddingService(),
                            new FaceMatchingService());
                    instance = local;
                }
            }
        }
        return local;
    }

    /** Test / App-startup constructor; not part of the public API. */
    FaceRecognitionPipeline(FaceDetectionService detectionService,
                            FaceEmbeddingService embeddingService,
                            FaceMatchingService matchingService) {
        this.detectionService = detectionService;
        this.embeddingService = embeddingService;
        this.matchingService = matchingService;
    }

    /** Replaces the {@link #getInstance()} singleton; test hook only. */
    static synchronized void setInstanceForTesting(FaceRecognitionPipeline pipeline) {
        instance = pipeline;
    }

    /**
     * Detect faces in the frame, embed each, and attach the best match (if any).
     * The source {@code frame} is used internally for alignCrop and is not retained
     * on return — callers may discard it immediately.
     */
    public List<RecognizedFace> process(BufferedImage frame) {
        if (frame == null) return List.of();

        List<DetectedFace> detected = detectionService.detectFaces(frame);
        if (detected.isEmpty()) return List.of();

        List<RecognizedFace> out = new ArrayList<>(detected.size());
        for (DetectedFace face : detected) {
            Embedding embedding = embeddingService.extractEmbedding(face, frame);
            List<MatchResult> matches = matchingService.findMatches(embedding);
            Optional<MatchResult> best = matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));

            out.add(new RecognizedFace(
                    face.getX(), face.getY(), face.getWidth(), face.getHeight(),
                    face.getConfidence(),
                    face.getCroppedFace(),
                    embedding,
                    best));
        }
        return out;
    }

    /**
     * Count faces in a photo's raw bytes. Used by the enrollment UI to validate
     * that exactly one face is visible before saving.
     */
    public int countFaces(byte[] photoBytes) {
        if (photoBytes == null) return 0;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(photoBytes));
            if (img == null) return 0;
            return detectionService.detectFaces(img).size();
        } catch (IOException e) {
            log.warn("countFaces: failed to read photo bytes", e);
            return 0;
        }
    }

    /**
     * Enroll a criminal: read their photos from the DB, detect → embed → persist.
     * Publishes into {@link EmbeddingStore} so live readers see the new vectors
     * on their next snapshot. Safe to call from a background thread.
     */
    public void enrollCriminal(long criminalId) {
        embeddingService.enrollCriminal(criminalId);
    }

    /**
     * Startup hook: re-enrolls everyone iff stored embeddings were produced by a
     * different face-recognition model than the one currently active. No-op otherwise.
     */
    public void migrateEmbeddingsIfNeeded() {
        embeddingService.migrateEmbeddingsIfNeeded();
    }
}
