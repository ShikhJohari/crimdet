package com.crimdet.model;

public class MatchResult {

    private long criminalId;
    private String criminalName;
    private double confidence;
    private CriminalStatus status;

    public MatchResult() {}

    public MatchResult(long criminalId, String criminalName, double confidence, CriminalStatus status) {
        this.criminalId = criminalId;
        this.criminalName = criminalName;
        this.confidence = confidence;
        this.status = status;
    }

    public long getCriminalId() { return criminalId; }
    public void setCriminalId(long criminalId) { this.criminalId = criminalId; }

    public String getCriminalName() { return criminalName; }
    public void setCriminalName(String criminalName) { this.criminalName = criminalName; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public CriminalStatus getStatus() { return status; }
    public void setStatus(CriminalStatus status) { this.status = status; }
}
