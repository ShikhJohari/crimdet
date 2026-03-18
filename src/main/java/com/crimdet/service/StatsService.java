package com.crimdet.service;

import com.crimdet.config.DatabaseConfig;
import com.crimdet.model.Criminal;
import com.crimdet.model.CriminalStatus;
import com.crimdet.model.DetectionLog;
import com.crimdet.repository.CriminalRepository;
import com.crimdet.repository.DetectionLogRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class StatsService {

    private final CriminalRepository criminalRepo;
    private final DetectionLogRepository detectionLogRepo;

    public StatsService() {
        var jdbi = DatabaseConfig.getInstance().getJdbi();
        this.criminalRepo = new CriminalRepository(jdbi);
        this.detectionLogRepo = new DetectionLogRepository(jdbi);
    }

    public long getWantedCount() {
        return criminalRepo.findAll().stream()
                .filter(c -> c.getStatus() == CriminalStatus.WANTED)
                .count();
    }

    public long getTotalCriminals() {
        return criminalRepo.findAll().size();
    }

    public long getTodayDetections() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime now = LocalDateTime.now();
        return detectionLogRepo.findByDateRange(todayStart, now).size();
    }

    public List<DetectionLog> getRecentDetections(int limit) {
        return detectionLogRepo.findAll(limit, 0);
    }
}
