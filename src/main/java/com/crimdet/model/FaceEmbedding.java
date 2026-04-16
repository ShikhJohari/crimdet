package com.crimdet.model;

import java.time.LocalDateTime;

public class FaceEmbedding {

    private long id;
    private long criminalId;
    private Long photoId;
    private String modelId;
    private byte[] embedding;
    private LocalDateTime createdAt;

    public FaceEmbedding() {}

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getCriminalId() { return criminalId; }
    public void setCriminalId(long criminalId) { this.criminalId = criminalId; }

    public Long getPhotoId() { return photoId; }
    public void setPhotoId(Long photoId) { this.photoId = photoId; }

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }

    public byte[] getEmbedding() { return embedding; }
    public void setEmbedding(byte[] embedding) { this.embedding = embedding; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
