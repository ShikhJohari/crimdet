package com.crimdet.repository;

import com.crimdet.model.FaceEmbedding;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.sql.Types;
import java.util.List;
import java.util.Optional;

public class FaceEmbeddingRepository {

    private final Jdbi jdbi;

    private static final RowMapper<FaceEmbedding> ROW_MAPPER = (rs, ctx) -> {
        FaceEmbedding e = new FaceEmbedding();
        e.setId(rs.getLong("id"));
        e.setCriminalId(rs.getLong("criminal_id"));
        long photoId = rs.getLong("photo_id");
        e.setPhotoId(rs.wasNull() ? null : photoId);
        e.setModelId(rs.getString("model_id"));
        e.setEmbedding(rs.getBytes("embedding"));
        e.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return e;
    };

    public FaceEmbeddingRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public long insert(FaceEmbedding embedding) {
        return jdbi.withHandle(handle ->
                handle.createUpdate("INSERT INTO face_embeddings (criminal_id, photo_id, model_id, embedding) " +
                                "VALUES (:criminalId, :photoId, :modelId, :embedding)")
                        .bind("criminalId", embedding.getCriminalId())
                        .bindBySqlType("photoId", embedding.getPhotoId(), Types.BIGINT)
                        .bind("modelId", embedding.getModelId())
                        .bind("embedding", embedding.getEmbedding())
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one()
        );
    }

    public List<FaceEmbedding> findByCriminalId(long criminalId) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM face_embeddings WHERE criminal_id = :criminalId ORDER BY id")
                        .bind("criminalId", criminalId)
                        .map(ROW_MAPPER)
                        .list()
        );
    }

    public Optional<FaceEmbedding> findFirst() {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM face_embeddings ORDER BY id LIMIT 1")
                        .map(ROW_MAPPER)
                        .findOne()
        );
    }

    public List<FaceEmbedding> findAll() {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM face_embeddings ORDER BY id")
                        .map(ROW_MAPPER)
                        .list()
        );
    }

    public void deleteByCriminalId(long criminalId) {
        jdbi.useHandle(handle ->
                handle.createUpdate("DELETE FROM face_embeddings WHERE criminal_id = :criminalId")
                        .bind("criminalId", criminalId)
                        .execute()
        );
    }
}
