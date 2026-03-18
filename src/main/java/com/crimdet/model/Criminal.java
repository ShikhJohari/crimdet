package com.crimdet.model;

import java.time.LocalDateTime;

public class Criminal {

    private long id;
    private String name;
    private String crimeType;
    private String description;
    private CriminalStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Criminal() {}

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCrimeType() { return crimeType; }
    public void setCrimeType(String crimeType) { this.crimeType = crimeType; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public CriminalStatus getStatus() { return status; }
    public void setStatus(CriminalStatus status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
