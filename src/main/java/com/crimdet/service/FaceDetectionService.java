package com.crimdet.service;

import com.crimdet.model.DetectedFace;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.RectVector;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.opencv.global.opencv_imgproc.*;

public class FaceDetectionService {

    private static final Logger log = LoggerFactory.getLogger(FaceDetectionService.class);

    private static FaceDetectionService instance;
    private static RuntimeException initError;
    private final CascadeClassifier classifier;

    private FaceDetectionService() {
        try {
            InputStream is = getClass().getResourceAsStream("/models/haarcascade_frontalface_default.xml");
            if (is == null) {
                throw new RuntimeException("Haar cascade resource not found");
            }
            Path tempFile = Files.createTempFile("haarcascade", ".xml");
            tempFile.toFile().deleteOnExit();
            Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
            is.close();

            classifier = new CascadeClassifier(tempFile.toString());
            if (classifier.empty()) {
                throw new RuntimeException("Failed to load cascade classifier");
            }
            log.info("Face detection classifier loaded");
        } catch (Throwable e) {
            throw new RuntimeException("Failed to initialize face detection: " + e.getMessage(), e);
        }
    }

    public static synchronized FaceDetectionService getInstance() {
        if (initError != null) {
            throw initError;
        }
        if (instance == null) {
            try {
                instance = new FaceDetectionService();
            } catch (RuntimeException e) {
                initError = e;
                throw e;
            }
        }
        return instance;
    }

    public synchronized List<DetectedFace> detectFaces(BufferedImage image) {
        List<DetectedFace> results = new ArrayList<>();

        Java2DFrameConverter java2dConverter = new Java2DFrameConverter();
        OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();
        Mat mat = matConverter.convert(java2dConverter.convert(image));

        if (mat == null || mat.empty()) {
            log.warn("Failed to convert image to Mat");
            return results;
        }

        Mat gray = new Mat();
        cvtColor(mat, gray, COLOR_BGR2GRAY);

        RectVector faces = new RectVector();
        classifier.detectMultiScale(gray, faces, 1.1, 3, 0, new Size(30, 30), new Size());

        log.info("Detected {} face(s)", faces.size());

        for (long i = 0; i < faces.size(); i++) {
            Rect rect = faces.get(i);
            int x = rect.x();
            int y = rect.y();
            int w = rect.width();
            int h = rect.height();

            // Clamp to image bounds
            int cropX = Math.max(0, x);
            int cropY = Math.max(0, y);
            int cropW = Math.min(w, image.getWidth() - cropX);
            int cropH = Math.min(h, image.getHeight() - cropY);

            BufferedImage cropped = image.getSubimage(cropX, cropY, cropW, cropH);

            // Haar cascade doesn't provide confidence; use a fixed value
            DetectedFace face = new DetectedFace(x, y, w, h, cropped, 1.0);
            results.add(face);
        }

        gray.close();
        mat.close();

        return results;
    }
}
