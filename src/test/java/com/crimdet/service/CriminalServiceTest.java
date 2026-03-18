package com.crimdet.service;

import com.crimdet.config.DatabaseConfig;
import com.crimdet.model.Criminal;
import com.crimdet.model.CriminalPhoto;
import com.crimdet.model.CriminalStatus;
import com.crimdet.repository.CriminalPhotoRepository;
import com.crimdet.repository.CriminalRepository;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CriminalServiceTest {

    private static DatabaseConfig dbConfig;
    private CriminalService service;

    @BeforeAll
    static void initDb() {
        dbConfig = DatabaseConfig.create("jdbc:h2:mem:service_test;DB_CLOSE_DELAY=-1");
    }

    @AfterAll
    static void closeDb() {
        dbConfig.close();
    }

    @BeforeEach
    void setUp() {
        var jdbi = dbConfig.getJdbi();
        var criminalRepo = new CriminalRepository(jdbi);
        var photoRepo = new CriminalPhotoRepository(jdbi);
        service = new CriminalService(criminalRepo, photoRepo);

        // Clean tables
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM criminal_photos");
            h.execute("DELETE FROM criminals");
        });
    }

    // --- CRUD ---

    @Test
    void addAndFindCriminal() {
        Criminal c = makeCriminal("John Doe", "Robbery", CriminalStatus.WANTED);
        long id = service.addCriminal(c);
        assertTrue(id > 0);

        Optional<Criminal> found = service.findById(id);
        assertTrue(found.isPresent());
        assertEquals("John Doe", found.get().getName());
        assertEquals("Robbery", found.get().getCrimeType());
        assertEquals(CriminalStatus.WANTED, found.get().getStatus());
    }

    @Test
    void updateCriminal() {
        Criminal c = makeCriminal("Original", "Theft", CriminalStatus.WANTED);
        long id = service.addCriminal(c);

        Criminal fetched = service.findById(id).orElseThrow();
        fetched.setName("Updated");
        fetched.setStatus(CriminalStatus.ARRESTED);
        service.updateCriminal(fetched);

        Criminal updated = service.findById(id).orElseThrow();
        assertEquals("Updated", updated.getName());
        assertEquals(CriminalStatus.ARRESTED, updated.getStatus());
    }

    @Test
    void deleteCriminal() {
        long id = service.addCriminal(makeCriminal("ToDelete", "Vandalism", CriminalStatus.WANTED));
        assertTrue(service.findById(id).isPresent());

        service.deleteCriminal(id);
        assertFalse(service.findById(id).isPresent());
    }

    // --- Search & Filter ---

    @Test
    void findAll() {
        service.addCriminal(makeCriminal("Alice", "Theft", CriminalStatus.WANTED));
        service.addCriminal(makeCriminal("Bob", "Fraud", CriminalStatus.ARRESTED));

        List<Criminal> all = service.findAll();
        assertEquals(2, all.size());
    }

    @Test
    void searchByName() {
        service.addCriminal(makeCriminal("Michael Johnson", "Theft", CriminalStatus.WANTED));
        service.addCriminal(makeCriminal("Sarah Connor", "Arson", CriminalStatus.WANTED));

        assertEquals(1, service.search("michael").size());
        assertEquals(1, service.search("connor").size());
        assertTrue(service.search("xyz").isEmpty());
    }

    @Test
    void findByStatus() {
        service.addCriminal(makeCriminal("A", "Theft", CriminalStatus.WANTED));
        service.addCriminal(makeCriminal("B", "Fraud", CriminalStatus.ARRESTED));
        service.addCriminal(makeCriminal("C", "Arson", CriminalStatus.WANTED));

        assertEquals(2, service.findByStatus(CriminalStatus.WANTED).size());
        assertEquals(1, service.findByStatus(CriminalStatus.ARRESTED).size());
        assertEquals(0, service.findByStatus(CriminalStatus.RELEASED).size());
    }

    @Test
    void searchAndFilter_nameOnly() {
        service.addCriminal(makeCriminal("Alice", "Theft", CriminalStatus.WANTED));
        service.addCriminal(makeCriminal("Bob", "Fraud", CriminalStatus.ARRESTED));

        List<Criminal> results = service.searchAndFilter("alice", null);
        assertEquals(1, results.size());
        assertEquals("Alice", results.get(0).getName());
    }

    @Test
    void searchAndFilter_statusOnly() {
        service.addCriminal(makeCriminal("Alice", "Theft", CriminalStatus.WANTED));
        service.addCriminal(makeCriminal("Bob", "Fraud", CriminalStatus.ARRESTED));

        List<Criminal> results = service.searchAndFilter(null, CriminalStatus.ARRESTED);
        assertEquals(1, results.size());
        assertEquals("Bob", results.get(0).getName());
    }

    @Test
    void searchAndFilter_both() {
        service.addCriminal(makeCriminal("Alice", "Theft", CriminalStatus.WANTED));
        service.addCriminal(makeCriminal("Alice B", "Fraud", CriminalStatus.ARRESTED));
        service.addCriminal(makeCriminal("Bob", "Arson", CriminalStatus.WANTED));

        List<Criminal> results = service.searchAndFilter("alice", CriminalStatus.WANTED);
        assertEquals(1, results.size());
        assertEquals("Alice", results.get(0).getName());
    }

    @Test
    void searchAndFilter_blankNameTreatedAsNull() {
        service.addCriminal(makeCriminal("Alice", "Theft", CriminalStatus.WANTED));
        service.addCriminal(makeCriminal("Bob", "Fraud", CriminalStatus.ARRESTED));

        // blank string = no name filter, so returns all, then filtered by status
        List<Criminal> results = service.searchAndFilter("  ", CriminalStatus.WANTED);
        assertEquals(1, results.size());
    }

    @Test
    void searchAndFilter_noFilters() {
        service.addCriminal(makeCriminal("Alice", "Theft", CriminalStatus.WANTED));
        service.addCriminal(makeCriminal("Bob", "Fraud", CriminalStatus.ARRESTED));

        List<Criminal> results = service.searchAndFilter(null, null);
        assertEquals(2, results.size());
    }

    // --- Photos ---

    @Test
    void addAndGetPhotos() {
        long crimId = service.addCriminal(makeCriminal("Alice", "Theft", CriminalStatus.WANTED));
        byte[] photoData = new byte[]{1, 2, 3, 4};

        long photoId = service.addPhoto(crimId, photoData);
        assertTrue(photoId > 0);

        List<CriminalPhoto> photos = service.getPhotos(crimId);
        assertEquals(1, photos.size());
        assertArrayEquals(photoData, photos.get(0).getPhotoData());
    }

    @Test
    void multiplePhotosPerCriminal() {
        long crimId = service.addCriminal(makeCriminal("Bob", "Fraud", CriminalStatus.WANTED));
        service.addPhoto(crimId, new byte[]{1});
        service.addPhoto(crimId, new byte[]{2});
        service.addPhoto(crimId, new byte[]{3});

        assertEquals(3, service.getPhotos(crimId).size());
    }

    @Test
    void deletePhoto() {
        long crimId = service.addCriminal(makeCriminal("Alice", "Theft", CriminalStatus.WANTED));
        long photoId = service.addPhoto(crimId, new byte[]{1, 2, 3});
        assertEquals(1, service.getPhotos(crimId).size());

        service.deletePhoto(photoId);
        assertTrue(service.getPhotos(crimId).isEmpty());
    }

    @Test
    void photosIsolatedBetweenCriminals() {
        long id1 = service.addCriminal(makeCriminal("Alice", "Theft", CriminalStatus.WANTED));
        long id2 = service.addCriminal(makeCriminal("Bob", "Fraud", CriminalStatus.ARRESTED));

        service.addPhoto(id1, new byte[]{1});
        service.addPhoto(id2, new byte[]{2});
        service.addPhoto(id2, new byte[]{3});

        assertEquals(1, service.getPhotos(id1).size());
        assertEquals(2, service.getPhotos(id2).size());
    }

    @Test
    void getPhotosForNonexistentCriminal() {
        assertTrue(service.getPhotos(99999).isEmpty());
    }

    // --- Helpers ---

    private Criminal makeCriminal(String name, String crimeType, CriminalStatus status) {
        Criminal c = new Criminal();
        c.setName(name);
        c.setCrimeType(crimeType);
        c.setStatus(status);
        return c;
    }
}
