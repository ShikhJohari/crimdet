package com.crimdet.repository;

import com.crimdet.model.DetectionLog;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;

public class DetectionLogRepository {

    private final Jdbi jdbi;

    private static final RowMapper<DetectionLog> ROW_MAPPER = (rs, ctx) -> {
        DetectionLog dl = new DetectionLog();
        dl.setId(rs.getLong("id"));
        long crimId = rs.getLong("criminal_id");
        dl.setCriminalId(rs.wasNull() ? null : crimId);
        dl.setConfidence(rs.getDouble("confidence"));
        dl.setScreenshot(rs.getBytes("screenshot"));
        dl.setDetectedAt(rs.getTimestamp("detected_at").toLocalDateTime());
        dl.setNotes(rs.getString("notes"));
        return dl;
    };

    public DetectionLogRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public long insert(DetectionLog log) {
        return jdbi.withHandle(handle ->
                handle.createUpdate("INSERT INTO detection_logs (criminal_id, confidence, screenshot, notes) VALUES (:criminalId, :confidence, :screenshot, :notes)")
                        .bindBySqlType("criminalId", log.getCriminalId(), Types.BIGINT)
                        .bind("confidence", log.getConfidence())
                        .bind("screenshot", log.getScreenshot())
                        .bind("notes", log.getNotes())
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one()
        );
    }

    public List<DetectionLog> findAll(int limit, int offset) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM detection_logs ORDER BY detected_at DESC LIMIT :limit OFFSET :offset")
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(ROW_MAPPER)
                        .list()
        );
    }

    public List<DetectionLog> findByCriminalId(long criminalId) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM detection_logs WHERE criminal_id = :criminalId ORDER BY detected_at DESC")
                        .bind("criminalId", criminalId)
                        .map(ROW_MAPPER)
                        .list()
        );
    }

    public List<DetectionLog> findByDateRange(LocalDateTime from, LocalDateTime to) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM detection_logs WHERE detected_at >= :from AND detected_at <= :to ORDER BY detected_at DESC")
                        .bind("from", from)
                        .bind("to", to)
                        .map(ROW_MAPPER)
                        .list()
        );
    }

    public List<DetectionLog> findByDateRange(LocalDateTime from, LocalDateTime to, int limit, int offset) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM detection_logs WHERE detected_at >= :from AND detected_at <= :to ORDER BY detected_at DESC LIMIT :limit OFFSET :offset")
                        .bind("from", from)
                        .bind("to", to)
                        .bind("limit", limit)
                        .bind("offset", offset)
                        .map(ROW_MAPPER)
                        .list()
        );
    }

    public long countByDateRange(LocalDateTime from, LocalDateTime to) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT COUNT(*) FROM detection_logs WHERE detected_at >= :from AND detected_at <= :to")
                        .bind("from", from)
                        .bind("to", to)
                        .mapTo(Long.class)
                        .one()
        );
    }

    public long count() {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT COUNT(*) FROM detection_logs")
                        .mapTo(Long.class)
                        .one()
        );
    }
}
