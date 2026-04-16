package com.crimdet.model;

import java.awt.image.BufferedImage;
import java.util.Optional;

/**
 * Public output type of {@link com.crimdet.service.FaceRecognitionPipeline#process}.
 * Carries the detection rectangle, the cropped face (for logging / UI thumbnails),
 * the extracted embedding, and the best match if one was found above threshold.
 *
 * The full source frame is deliberately not held here — the pipeline owns the
 * frame's lifetime during {@code process()} and does not leak it to callers.
 */
public record RecognizedFace(
        int x,
        int y,
        int width,
        int height,
        double detectionConfidence,
        BufferedImage croppedFace,
        Embedding embedding,
        Optional<MatchResult> match) {
}
