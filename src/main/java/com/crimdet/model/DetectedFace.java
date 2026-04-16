package com.crimdet.model;

import java.awt.image.BufferedImage;

/**
 * Detector output: a face rectangle with its cropped image and optional 5-point landmarks.
 *
 * This is an internal type produced by the detection stage and consumed by the embedding
 * stage inside {@link com.crimdet.service.FaceRecognitionPipeline}. Callers outside the
 * pipeline work with {@link RecognizedFace} instead.
 */
public class DetectedFace {

    private int x;
    private int y;
    private int width;
    private int height;
    private BufferedImage croppedFace;
    private double confidence;

    // FaceDetectorYN detection row: [x, y, w, h, x_re, y_re, x_le, y_le, x_nt, y_nt, x_rcm, y_rcm, x_lcm, y_lcm, score].
    // Null when the detector doesn't produce landmarks (DNN SSD, Haar).
    private float[] detectionRow;

    public DetectedFace() {}

    public DetectedFace(int x, int y, int width, int height, BufferedImage croppedFace, double confidence) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.croppedFace = croppedFace;
        this.confidence = confidence;
    }

    public int getX() { return x; }
    public void setX(int x) { this.x = x; }

    public int getY() { return y; }
    public void setY(int y) { this.y = y; }

    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }

    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }

    public BufferedImage getCroppedFace() { return croppedFace; }
    public void setCroppedFace(BufferedImage croppedFace) { this.croppedFace = croppedFace; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public float[] getDetectionRow() { return detectionRow; }
    public void setDetectionRow(float[] detectionRow) { this.detectionRow = detectionRow; }
}
