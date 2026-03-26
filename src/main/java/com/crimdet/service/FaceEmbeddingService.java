package com.crimdet.service;

import com.crimdet.config.DatabaseConfig;
import com.crimdet.model.Criminal;
import com.crimdet.model.CriminalPhoto;
import com.crimdet.model.DetectedFace;
import com.crimdet.model.FaceEmbedding;
import com.crimdet.repository.CriminalPhotoRepository;
import com.crimdet.repository.CriminalRepository;
import com.crimdet.repository.FaceEmbeddingRepository;
import com.crimdet.util.EmbeddingUtils;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_objdetect.FaceRecognizerSF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

public class FaceEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(FaceEmbeddingService.class);
    private static final int EMBED_LENGTH = 128;

    private final FaceDetectionService faceDetectionService;
    private final FaceEmbeddingRepository embeddingRepo;
    private final CriminalPhotoRepository photoRepo;
    private final FaceRecognizerSF faceRecognizer;

    public FaceEmbeddingService() {
        var jdbi = DatabaseConfig.getInstance().getJdbi();
        this.faceDetectionService = FaceDetectionService.getInstance();
        this.embeddingRepo = new FaceEmbeddingRepository(jdbi);
        this.photoRepo = new CriminalPhotoRepository(jdbi);
        this.faceRecognizer = loadFaceRecognizer();
    }

    public FaceEmbeddingService(FaceDetectionService faceDetectionService,
                                FaceEmbeddingRepository embeddingRepo,
                                CriminalPhotoRepository photoRepo) {
        this.faceDetectionService = faceDetectionService;
        this.embeddingRepo = embeddingRepo;
        this.photoRepo = photoRepo;
        this.faceRecognizer = loadFaceRecognizer();
    }

    private FaceRecognizerSF loadFaceRecognizer() {
        try (InputStream is = getClass().getResourceAsStream("/models/face_recognition_sface_2021dec_int8.onnx")) {
            if (is == null) {
                throw new RuntimeException("SFace ONNX model not found in resources");
            }
            Path tempFile = Files.createTempFile("sface_model", ".onnx");
            tempFile.toFile().deleteOnExit();
            Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);

            FaceRecognizerSF recognizer = FaceRecognizerSF.create(tempFile.toString(), "");
            log.info("SFace face recognition model loaded");
            return recognizer;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load SFace model: " + e.getMessage(), e);
        }
    }

    /**
     * Extract embedding from a DetectedFace. Uses alignCrop when landmarks are available
     * (YuNet detection), falls back to raw crop when they're not (DNN SSD / Haar).
     */
    public synchronized float[] extractEmbedding(DetectedFace face) {
        if (face.getDetectionRow() != null && face.getOriginalImage() != null) {
            return extractEmbeddingAligned(face.getOriginalImage(), face.getDetectionRow());
        }
        // Fallback for detectors without landmarks (DNN SSD, Haar)
        return extractEmbeddingFromCrop(face.getCroppedFace());
    }

    /**
     * Aligned extraction: uses FaceRecognizerSF.alignCrop with 5-point landmarks
     * from FaceDetectorYN for consistent, pose-invariant embeddings.
     */
    private float[] extractEmbeddingAligned(BufferedImage originalImage, float[] detectionRow) {
        Java2DFrameConverter java2dConverter = new Java2DFrameConverter();
        OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();
        Mat imageMat = matConverter.convert(java2dConverter.convert(originalImage));

        if (imageMat == null || imageMat.empty()) {
            log.warn("Failed to convert original image to Mat");
            return new float[EMBED_LENGTH];
        }

        Mat bgr = null;
        Mat aligned = new Mat();
        Mat embedding = new Mat();
        Mat faceRow = null;
        try {
            // Ensure 3-channel BGR
            if (imageMat.channels() == 4) {
                bgr = new Mat();
                cvtColor(imageMat, bgr, COLOR_BGRA2BGR);
            } else if (imageMat.channels() == 1) {
                bgr = new Mat();
                cvtColor(imageMat, bgr, COLOR_GRAY2BGR);
            } else {
                bgr = imageMat;
            }

            // Build a 1x15 float Mat from the detection row
            faceRow = new Mat(1, 15, CV_32F);
            FloatPointer fp = new FloatPointer(faceRow.ptr());
            fp.put(detectionRow);
            fp.close();

            // alignCrop uses the 5 landmarks (indices 4-13) to compute a similarity
            // transform that warps the face to canonical 112x112 positions
            faceRecognizer.alignCrop(bgr, faceRow, aligned);

            // Extract 128-D embedding from the aligned face
            faceRecognizer.feature(aligned, embedding);

            FloatPointer embPtr = new FloatPointer(embedding.ptr());
            float[] result = new float[EMBED_LENGTH];
            embPtr.get(result);
            embPtr.close();
            return result;
        } finally {
            embedding.close();
            aligned.close();
            if (faceRow != null) faceRow.close();
            if (bgr != null && bgr != imageMat) bgr.close();
            imageMat.close();
        }
    }

    /**
     * Extract embedding from a raw face image (no alignment).
     * Used by tests with synthetic images and as fallback when detector has no landmarks.
     */
    public synchronized float[] extractEmbedding(BufferedImage faceImage) {
        return extractEmbeddingFromCrop(faceImage);
    }

    private float[] extractEmbeddingFromCrop(BufferedImage faceImage) {
        Java2DFrameConverter java2dConverter = new Java2DFrameConverter();
        OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();
        Mat mat = matConverter.convert(java2dConverter.convert(faceImage));

        if (mat == null || mat.empty()) {
            log.warn("Failed to convert face image to Mat");
            return new float[EMBED_LENGTH];
        }

        Mat bgr = null;
        Mat embedding = new Mat();
        try {
            if (mat.channels() == 4) {
                bgr = new Mat();
                cvtColor(mat, bgr, COLOR_BGRA2BGR);
            } else if (mat.channels() == 1) {
                bgr = new Mat();
                cvtColor(mat, bgr, COLOR_GRAY2BGR);
            } else {
                bgr = mat;
            }

            faceRecognizer.feature(bgr, embedding);

            FloatPointer fp = new FloatPointer(embedding.ptr());
            float[] result = new float[EMBED_LENGTH];
            fp.get(result);
            fp.close();
            return result;
        } finally {
            embedding.close();
            if (bgr != null && bgr != mat) bgr.close();
            mat.close();
        }
    }

    public void enrollCriminal(long criminalId) {
        // Remove existing embeddings for this criminal
        embeddingRepo.deleteByCriminalId(criminalId);

        List<CriminalPhoto> photos = photoRepo.findByCriminalId(criminalId);
        int enrolled = 0;

        for (CriminalPhoto photo : photos) {
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(photo.getPhotoData()));
                if (image == null) {
                    log.warn("Could not read photo id={}", photo.getId());
                    continue;
                }

                List<DetectedFace> faces = faceDetectionService.detectFaces(image);
                if (faces.isEmpty()) {
                    log.warn("No face detected in photo id={}", photo.getId());
                    continue;
                }

                // Use the first (largest) detected face
                DetectedFace face = faces.get(0);
                float[] embedding = extractEmbedding(face);

                FaceEmbedding fe = new FaceEmbedding();
                fe.setCriminalId(criminalId);
                fe.setPhotoId(photo.getId());
                fe.setEmbedding(EmbeddingUtils.toBytes(embedding));
                embeddingRepo.insert(fe);
                enrolled++;
            } catch (IOException e) {
                log.error("Failed to process photo id={}", photo.getId(), e);
            }
        }

        log.info("Enrolled criminal id={}: {}/{} photos processed", criminalId, enrolled, photos.size());
    }

    /**
     * Checks if stored embeddings are stale (wrong dimension) and re-enrolls all criminals if so.
     */
    public void migrateEmbeddingsIfNeeded() {
        var firstEmbedding = embeddingRepo.findFirst();
        if (firstEmbedding.isEmpty()) {
            log.info("No embeddings found, skipping migration");
            return;
        }

        float[] floats = EmbeddingUtils.toFloats(firstEmbedding.get().getEmbedding());
        boolean dimensionMismatch = floats.length != EMBED_LENGTH;
        // Force re-enrollment: existing 128-d embeddings may have been generated
        // without face alignment (alignCrop). Always re-enroll to ensure quality.
        boolean forceReenroll = true;

        if (!dimensionMismatch && !forceReenroll) {
            log.info("Embeddings are current (dim={}), no migration needed", EMBED_LENGTH);
            return;
        }

        log.info("Re-enrolling all criminals (dim={}, expected={}, forceReenroll={})",
                floats.length, EMBED_LENGTH, forceReenroll);

        var jdbi = DatabaseConfig.getInstance().getJdbi();
        var criminalRepo = new CriminalRepository(jdbi);
        List<Criminal> criminals = criminalRepo.findAll();

        for (int i = 0; i < criminals.size(); i++) {
            Criminal c = criminals.get(i);
            log.info("Re-enrolling criminal {}/{}: id={} name='{}'",
                    i + 1, criminals.size(), c.getId(), c.getName());
            try {
                enrollCriminal(c.getId());
            } catch (Exception e) {
                log.error("Failed to re-enroll criminal id={}", c.getId(), e);
            }
        }

        log.info("Embedding migration complete: {} criminals re-enrolled", criminals.size());
    }
}
