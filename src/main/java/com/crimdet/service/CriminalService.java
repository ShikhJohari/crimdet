package com.crimdet.service;

import com.crimdet.config.DatabaseConfig;
import com.crimdet.model.Criminal;
import com.crimdet.model.CriminalPhoto;
import com.crimdet.model.CriminalStatus;
import com.crimdet.repository.CriminalPhotoRepository;
import com.crimdet.repository.CriminalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

public class CriminalService {

    private static final Logger log = LoggerFactory.getLogger(CriminalService.class);

    private final CriminalRepository criminalRepo;
    private final CriminalPhotoRepository photoRepo;

    public CriminalService() {
        var jdbi = DatabaseConfig.getInstance().getJdbi();
        this.criminalRepo = new CriminalRepository(jdbi);
        this.photoRepo = new CriminalPhotoRepository(jdbi);
    }

    public CriminalService(CriminalRepository criminalRepo, CriminalPhotoRepository photoRepo) {
        this.criminalRepo = criminalRepo;
        this.photoRepo = photoRepo;
    }

    public long addCriminal(Criminal criminal) {
        long id = criminalRepo.insert(criminal);
        log.info("Added criminal: {} (id={})", criminal.getName(), id);
        return id;
    }

    public void updateCriminal(Criminal criminal) {
        criminalRepo.update(criminal);
        log.info("Updated criminal: {} (id={})", criminal.getName(), criminal.getId());
    }

    public void deleteCriminal(long id) {
        criminalRepo.delete(id);
        log.info("Deleted criminal id={}", id);
    }

    public Optional<Criminal> findById(long id) {
        return criminalRepo.findById(id);
    }

    public List<Criminal> findAll() {
        return criminalRepo.findAll();
    }

    public List<Criminal> search(String name) {
        return criminalRepo.search(name);
    }

    public List<Criminal> findByStatus(CriminalStatus status) {
        return criminalRepo.findAll().stream()
                .filter(c -> c.getStatus() == status)
                .toList();
    }

    public List<Criminal> searchAndFilter(String nameQuery, CriminalStatus status) {
        List<Criminal> results;
        if (nameQuery != null && !nameQuery.isBlank()) {
            results = criminalRepo.search(nameQuery);
        } else {
            results = criminalRepo.findAll();
        }
        if (status != null) {
            results = results.stream().filter(c -> c.getStatus() == status).toList();
        }
        return results;
    }

    // Photo operations
    public long addPhoto(long criminalId, byte[] photoData) {
        CriminalPhoto photo = new CriminalPhoto();
        photo.setCriminalId(criminalId);
        photo.setPhotoData(photoData);
        long id = photoRepo.insert(photo);
        log.info("Added photo id={} for criminal id={}", id, criminalId);
        return id;
    }

    public List<CriminalPhoto> getPhotos(long criminalId) {
        return photoRepo.findByCriminalId(criminalId);
    }

    public void deletePhoto(long photoId) {
        photoRepo.delete(photoId);
        log.info("Deleted photo id={}", photoId);
    }
}
