package com.crimdet.service;

import com.crimdet.model.DetectedFace;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.RectVector;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_dnn.Net;
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

import static org.bytedeco.opencv.global.opencv_dnn.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

public class FaceDetectionService {

    private static final Logger log = LoggerFactory.getLogger(FaceDetectionService.class);

    private static final double DNN_CONFIDENCE_THRESHOLD = 0.5;

    private static FaceDetectionService instance;
    private static RuntimeException initError;

    private Net dnnNet;
    private boolean useDnn;
    private final CascadeClassifier classifier;

    private FaceDetectionService() {
        try {
            // Try loading DNN SSD model as primary detector
            dnnNet = loadDnnModel();
            useDnn = (dnnNet != null);

            // Always load Haar cascade (as fallback or if DNN fails at runtime)
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

            if (useDnn) {
                log.info("Face detection initialized: DNN SSD primary, Haar fallback");
            } else {
                log.info("Face detection initialized: Haar cascade only");
            }
        } catch (Throwable e) {
            throw new RuntimeException("Failed to initialize face detection: " + e.getMessage(), e);
        }
    }

    private Net loadDnnModel() {
        try {
            InputStream protoIs = getClass().getResourceAsStream("/models/deploy.prototxt");
            InputStream modelIs = getClass().getResourceAsStream("/models/res10_300x300_ssd_iter_140000.caffemodel");

            if (protoIs == null || modelIs == null) {
                log.warn("DNN model resources not found, falling back to Haar cascade");
                if (protoIs != null) protoIs.close();
                if (modelIs != null) modelIs.close();
                return null;
            }

            Path protoFile = Files.createTempFile("deploy", ".prototxt");
            protoFile.toFile().deleteOnExit();
            Files.copy(protoIs, protoFile, StandardCopyOption.REPLACE_EXISTING);
            protoIs.close();

            Path modelFile = Files.createTempFile("res10_ssd", ".caffemodel");
            modelFile.toFile().deleteOnExit();
            Files.copy(modelIs, modelFile, StandardCopyOption.REPLACE_EXISTING);
            modelIs.close();

            Net net = readNetFromCaffe(protoFile.toString(), modelFile.toString());
            if (net.empty()) {
                log.warn("DNN model loaded but is empty, falling back to Haar cascade");
                return null;
            }

            log.info("DNN SSD face detection model loaded");
            return net;
        } catch (Throwable e) {
            log.warn("Failed to load DNN model, falling back to Haar cascade: {}", e.getMessage());
            return null;
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
        if (useDnn) {
            try {
                return detectFacesDnn(image);
            } catch (Throwable e) {
                log.warn("DNN detection failed, falling back to Haar: {}", e.getMessage());
                return detectFacesHaar(image);
            }
        }
        return detectFacesHaar(image);
    }

    private List<DetectedFace> detectFacesDnn(BufferedImage image) {
        List<DetectedFace> results = new ArrayList<>();

        Java2DFrameConverter java2dConverter = new Java2DFrameConverter();
        OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();
        Mat mat = matConverter.convert(java2dConverter.convert(image));

        if (mat == null || mat.empty()) {
            log.warn("Failed to convert image to Mat");
            return results;
        }

        Mat bgr = null;
        Mat blob = null;
        Mat detections = null;
        Mat detectionMat = null;
        try {
            int imgWidth = mat.cols();
            int imgHeight = mat.rows();

            // Ensure 3-channel BGR (Java2DFrameConverter may produce 4-channel BGRA)
            if (mat.channels() == 4) {
                bgr = new Mat();
                cvtColor(mat, bgr, COLOR_BGRA2BGR);
            } else if (mat.channels() == 1) {
                bgr = new Mat();
                cvtColor(mat, bgr, COLOR_GRAY2BGR);
            } else {
                bgr = mat;
            }

            // Create blob: resize to 300x300, mean subtraction (104, 177, 123)
            blob = blobFromImage(bgr, 1.0, new Size(300, 300),
                    new Scalar(104.0, 177.0, 123.0, 0.0), false, false, org.bytedeco.opencv.global.opencv_core.CV_32F);

            dnnNet.setInput(blob);
            detections = dnnNet.forward();

            // Output shape is [1, 1, N, 7] where each detection is:
            // [batchId, classId, confidence, left, top, right, bottom]
            // Reshape to [N, 7] for easier access
            detectionMat = detections.reshape(1, detections.total() > 0 ? (int) (detections.total() / 7) : 0);

            for (int i = 0; i < detectionMat.rows(); i++) {
                float confidence = detectionMat.ptr(i, 2).getFloat();

                if (confidence < DNN_CONFIDENCE_THRESHOLD) {
                    continue;
                }

                // Scale bounding box back to original image size
                int x = (int) (detectionMat.ptr(i, 3).getFloat() * imgWidth);
                int y = (int) (detectionMat.ptr(i, 4).getFloat() * imgHeight);
                int x2 = (int) (detectionMat.ptr(i, 5).getFloat() * imgWidth);
                int y2 = (int) (detectionMat.ptr(i, 6).getFloat() * imgHeight);

                // Clamp to image bounds
                int cropX = Math.max(0, x);
                int cropY = Math.max(0, y);
                int cropX2 = Math.min(imgWidth, x2);
                int cropY2 = Math.min(imgHeight, y2);
                int w = cropX2 - cropX;
                int h = cropY2 - cropY;

                if (w <= 0 || h <= 0) {
                    continue;
                }

                BufferedImage cropped = image.getSubimage(cropX, cropY, w, h);
                results.add(new DetectedFace(cropX, cropY, w, h, cropped, confidence));
            }

            log.info("DNN detected {} face(s)", results.size());
        } finally {
            if (detectionMat != null) detectionMat.close();
            if (detections != null) detections.close();
            if (blob != null) blob.close();
            if (bgr != null && bgr != mat) bgr.close();
            mat.close();
        }

        return results;
    }

    private List<DetectedFace> detectFacesHaar(BufferedImage image) {
        List<DetectedFace> results = new ArrayList<>();

        Java2DFrameConverter java2dConverter = new Java2DFrameConverter();
        OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();
        Mat mat = matConverter.convert(java2dConverter.convert(image));

        if (mat == null || mat.empty()) {
            log.warn("Failed to convert image to Mat");
            return results;
        }

        Mat gray = new Mat();
        if (mat.channels() == 4) {
            cvtColor(mat, gray, COLOR_BGRA2GRAY);
        } else {
            cvtColor(mat, gray, COLOR_BGR2GRAY);
        }

        RectVector faces = new RectVector();
        // Tightened params: minNeighbors 5, minSize 80x80
        classifier.detectMultiScale(gray, faces, 1.1, 5, 0, new Size(80, 80), new Size());

        log.info("Haar detected {} face(s)", faces.size());

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

            // Haar doesn't provide confidence; use fixed value
            results.add(new DetectedFace(x, y, w, h, cropped, 1.0));
        }

        gray.close();
        mat.close();

        return results;
    }
}
