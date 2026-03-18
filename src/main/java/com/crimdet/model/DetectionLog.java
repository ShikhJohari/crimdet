package com.crimdet.model;

import java.time.LocalDateTime;

public class DetectionLog {

    private long id;
    private Long criminalId;
    private double confidence;
    private byte[] screenshot;
    private LocalDateTime detectedAt;
    private String notes;

    public DetectionLog() {}

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public Long getCriminalId() { return criminalId; }
    public void setCriminalId(Long criminalId) { this.criminalId = criminalId; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public byte[] getScreenshot() { return screenshot; }
    public void setScreenshot(byte[] screenshot) { this.screenshot = screenshot; }

    public LocalDateTime getDetectedAt() { return detectedAt; }
    public void setDetectedAt(LocalDateTime detectedAt) { this.detectedAt = detectedAt; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
