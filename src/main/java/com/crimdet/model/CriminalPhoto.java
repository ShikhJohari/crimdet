package com.crimdet.model;

import java.time.LocalDateTime;

public class CriminalPhoto {

    private long id;
    private long criminalId;
    private byte[] photoData;
    private LocalDateTime createdAt;

    public CriminalPhoto() {}

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getCriminalId() { return criminalId; }
    public void setCriminalId(long criminalId) { this.criminalId = criminalId; }

    public byte[] getPhotoData() { return photoData; }
    public void setPhotoData(byte[] photoData) { this.photoData = photoData; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
