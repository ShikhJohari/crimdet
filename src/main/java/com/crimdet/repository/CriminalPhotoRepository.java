package com.crimdet.repository;

import com.crimdet.model.CriminalPhoto;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

public class CriminalPhotoRepository {

    private final Jdbi jdbi;

    public CriminalPhotoRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public long insert(CriminalPhoto photo) {
        return jdbi.withHandle(handle ->
                handle.createUpdate("INSERT INTO criminal_photos (criminal_id, photo_data) VALUES (:criminalId, :photoData)")
                        .bind("criminalId", photo.getCriminalId())
                        .bind("photoData", photo.getPhotoData())
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one()
        );
    }

    public List<CriminalPhoto> findByCriminalId(long criminalId) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM criminal_photos WHERE criminal_id = :criminalId ORDER BY id")
                        .bind("criminalId", criminalId)
                        .map((rs, ctx) -> {
                            CriminalPhoto p = new CriminalPhoto();
                            p.setId(rs.getLong("id"));
                            p.setCriminalId(rs.getLong("criminal_id"));
                            p.setPhotoData(rs.getBytes("photo_data"));
                            p.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                            return p;
                        })
                        .list()
        );
    }

    public void delete(long id) {
        jdbi.useHandle(handle ->
                handle.createUpdate("DELETE FROM criminal_photos WHERE id = :id")
                        .bind("id", id)
                        .execute()
        );
    }
}
