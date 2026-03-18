package com.crimdet.repository;

import com.crimdet.model.Criminal;
import com.crimdet.model.CriminalStatus;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public class CriminalRepository {

    private final Jdbi jdbi;

    private static final RowMapper<Criminal> ROW_MAPPER = (rs, ctx) -> {
        Criminal c = new Criminal();
        c.setId(rs.getLong("id"));
        c.setName(rs.getString("name"));
        c.setCrimeType(rs.getString("crime_type"));
        c.setDescription(rs.getString("description"));
        c.setStatus(CriminalStatus.valueOf(rs.getString("status")));
        c.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        c.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        return c;
    };

    public CriminalRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public long insert(Criminal criminal) {
        return jdbi.withHandle(handle ->
                handle.createUpdate("INSERT INTO criminals (name, crime_type, description, status) VALUES (:name, :crimeType, :description, :status)")
                        .bind("name", criminal.getName())
                        .bind("crimeType", criminal.getCrimeType())
                        .bind("description", criminal.getDescription())
                        .bind("status", criminal.getStatus() != null ? criminal.getStatus().name() : CriminalStatus.WANTED.name())
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one()
        );
    }

    public Optional<Criminal> findById(long id) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM criminals WHERE id = :id")
                        .bind("id", id)
                        .map(ROW_MAPPER)
                        .findOne()
        );
    }

    public List<Criminal> findAll() {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM criminals ORDER BY id")
                        .map(ROW_MAPPER)
                        .list()
        );
    }

    public List<Criminal> search(String name) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM criminals WHERE LOWER(name) LIKE :pattern ORDER BY id")
                        .bind("pattern", "%" + name.toLowerCase() + "%")
                        .map(ROW_MAPPER)
                        .list()
        );
    }

    public void update(Criminal criminal) {
        jdbi.useHandle(handle ->
                handle.createUpdate("UPDATE criminals SET name = :name, crime_type = :crimeType, description = :description, status = :status, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                        .bind("id", criminal.getId())
                        .bind("name", criminal.getName())
                        .bind("crimeType", criminal.getCrimeType())
                        .bind("description", criminal.getDescription())
                        .bind("status", criminal.getStatus() != null ? criminal.getStatus().name() : CriminalStatus.WANTED.name())
                        .execute()
        );
    }

    public void delete(long id) {
        jdbi.useHandle(handle ->
                handle.createUpdate("DELETE FROM criminals WHERE id = :id")
                        .bind("id", id)
                        .execute()
        );
    }
}
