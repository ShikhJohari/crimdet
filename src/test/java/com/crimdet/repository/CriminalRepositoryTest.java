package com.crimdet.repository;

import com.crimdet.config.DatabaseConfig;
import com.crimdet.model.Criminal;
import com.crimdet.model.CriminalStatus;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CriminalRepositoryTest {

    private static DatabaseConfig dbConfig;
    private CriminalRepository repo;

    @BeforeAll
    static void initDb() {
        dbConfig = DatabaseConfig.create("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1");
    }

    @AfterAll
    static void closeDb() {
        dbConfig.close();
    }

    @BeforeEach
    void setUp() {
        repo = new CriminalRepository(dbConfig.getJdbi());
        // Clean table before each test
        dbConfig.getJdbi().useHandle(h -> h.execute("DELETE FROM criminals"));
    }

    @Test
    void insertAndFindById() {
        Criminal c = new Criminal();
        c.setName("John Doe");
        c.setCrimeType("Robbery");
        c.setDescription("Armed robbery suspect");
        c.setStatus(CriminalStatus.WANTED);

        long id = repo.insert(c);
        assertTrue(id > 0);

        Optional<Criminal> found = repo.findById(id);
        assertTrue(found.isPresent());

        Criminal result = found.get();
        assertEquals("John Doe", result.getName());
        assertEquals("Robbery", result.getCrimeType());
        assertEquals("Armed robbery suspect", result.getDescription());
        assertEquals(CriminalStatus.WANTED, result.getStatus());
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getUpdatedAt());
    }

    @Test
    void insertWithDefaultStatus() {
        Criminal c = new Criminal();
        c.setName("Jane Smith");
        c.setCrimeType("Fraud");

        long id = repo.insert(c);
        Optional<Criminal> found = repo.findById(id);
        assertTrue(found.isPresent());
        assertEquals(CriminalStatus.WANTED, found.get().getStatus());
    }

    @Test
    void findAll() {
        Criminal c1 = new Criminal();
        c1.setName("Alice");
        c1.setCrimeType("Theft");
        c1.setStatus(CriminalStatus.WANTED);
        repo.insert(c1);

        Criminal c2 = new Criminal();
        c2.setName("Bob");
        c2.setCrimeType("Assault");
        c2.setStatus(CriminalStatus.ARRESTED);
        repo.insert(c2);

        List<Criminal> all = repo.findAll();
        assertEquals(2, all.size());
    }

    @Test
    void searchByName() {
        Criminal c1 = new Criminal();
        c1.setName("Michael Johnson");
        c1.setCrimeType("Theft");
        c1.setStatus(CriminalStatus.WANTED);
        repo.insert(c1);

        Criminal c2 = new Criminal();
        c2.setName("Sarah Connor");
        c2.setCrimeType("Arson");
        c2.setStatus(CriminalStatus.WANTED);
        repo.insert(c2);

        List<Criminal> results = repo.search("michael");
        assertEquals(1, results.size());
        assertEquals("Michael Johnson", results.get(0).getName());

        results = repo.search("johnson");
        assertEquals(1, results.size());

        results = repo.search("xyz");
        assertTrue(results.isEmpty());
    }

    @Test
    void update() {
        Criminal c = new Criminal();
        c.setName("Original Name");
        c.setCrimeType("Theft");
        c.setStatus(CriminalStatus.WANTED);

        long id = repo.insert(c);
        Criminal fetched = repo.findById(id).orElseThrow();

        fetched.setName("Updated Name");
        fetched.setStatus(CriminalStatus.ARRESTED);
        repo.update(fetched);

        Criminal updated = repo.findById(id).orElseThrow();
        assertEquals("Updated Name", updated.getName());
        assertEquals(CriminalStatus.ARRESTED, updated.getStatus());
    }

    @Test
    void delete() {
        Criminal c = new Criminal();
        c.setName("To Delete");
        c.setCrimeType("Vandalism");
        c.setStatus(CriminalStatus.WANTED);

        long id = repo.insert(c);
        assertTrue(repo.findById(id).isPresent());

        repo.delete(id);
        assertFalse(repo.findById(id).isPresent());
    }

    @Test
    void findByIdNotFound() {
        Optional<Criminal> found = repo.findById(99999);
        assertFalse(found.isPresent());
    }
}
