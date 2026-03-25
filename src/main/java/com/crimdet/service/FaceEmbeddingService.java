package com.crimdet.service;

import com.crimdet.config.DatabaseConfig;
import com.crimdet.model.CriminalPhoto;
import com.crimdet.model.DetectedFace;
import com.crimdet.model.FaceEmbedding;
import com.crimdet.repository.CriminalPhotoRepository;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

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

    public synchronized float[] extractEmbedding(BufferedImage faceImage) {
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
            // Ensure 3-channel BGR
            if (mat.channels() == 4) {
                bgr = new Mat();
                cvtColor(mat, bgr, COLOR_BGRA2BGR);
            } else if (mat.channels() == 1) {
                bgr = new Mat();
                cvtColor(mat, bgr, COLOR_GRAY2BGR);
            } else {
                bgr = mat;
            }

            // SFace handles resize to 112x112 internally; output is already L2-normalized
            faceRecognizer.feature(bgr, embedding);

            // Extract float[] from 1x128 output Mat
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
                float[] embedding = extractEmbedding(face.getCroppedFace());

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
}
