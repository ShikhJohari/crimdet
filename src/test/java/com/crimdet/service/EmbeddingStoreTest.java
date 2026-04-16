package com.crimdet.service;

import com.crimdet.config.DatabaseConfig;
import com.crimdet.model.CriminalEmbeddings;
import com.crimdet.model.Embedding;
import com.crimdet.model.FaceEmbedding;
import com.crimdet.repository.FaceEmbeddingRepository;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EmbeddingStore — the single source of truth for in-memory embeddings.
 *
 * Covers the correctness guarantees that Candidate #3 exists to provide:
 * - snapshot returns defensive copies (mutation does not leak back into the store)
 * - reloadForCriminal picks up new DB rows
 * - evictForCriminal drops stale entries
 * - concurrent reload + snapshot does not throw or tear
 */
class EmbeddingStoreTest {

    private static DatabaseConfig db;
    private FaceEmbeddingRepository embeddingRepo;
    private EmbeddingStore store;

    @BeforeAll
    static void initDb() {
        db = DatabaseConfig.create("jdbc:h2:mem:storetest;DB_CLOSE_DELAY=-1");
    }

    @AfterAll
    static void tearDownDb() {
        if (db != null) db.close();
    }

    @BeforeEach
    void setUp() {
        var jdbi = db.getJdbi();
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM face_embeddings");
            h.execute("DELETE FROM criminal_photos");
            h.execute("DELETE FROM criminals");
        });
        embeddingRepo = new FaceEmbeddingRepository(jdbi);
        store = new EmbeddingStore(embeddingRepo);
    }

    /** Inserts a criminal row with the given id (satisfies face_embeddings FK). */
    private void ensureCriminal(long id) {
        db.getJdbi().useHandle(h -> h.createUpdate(
                        "INSERT INTO criminals (id, name, crime_type, status) VALUES (:id, :name, 'Test', 'WANTED')")
                .bind("id", id)
                .bind("name", "Criminal " + id)
                .execute());
    }

    private FaceEmbedding insertRow(long criminalId, String modelId, float... vector) {
        ensureCriminalIdempotent(criminalId);
        Embedding emb = new Embedding(vector, modelId);
        FaceEmbedding fe = new FaceEmbedding();
        fe.setCriminalId(criminalId);
        fe.setModelId(modelId);
        fe.setEmbedding(emb.toBytes());
        long id = embeddingRepo.insert(fe);
        fe.setId(id);
        return fe;
    }

    private void ensureCriminalIdempotent(long id) {
        db.getJdbi().useHandle(h -> {
            boolean exists = h.createQuery("SELECT COUNT(*) FROM criminals WHERE id = :id")
                    .bind("id", id)
                    .mapTo(Long.class)
                    .one() > 0;
            if (!exists) ensureCriminal(id);
        });
    }

    @Test
    void emptyDb_emptyStore() {
        assertEquals(0, store.criminalCount());
        assertTrue(store.snapshot().isEmpty());
    }

    @Test
    void initialLoadPicksUpExistingRows() {
        insertRow(1L, "modelX", 1f, 0f);
        insertRow(1L, "modelX", 0f, 1f);
        insertRow(2L, "modelX", 1f, 1f);

        store = new EmbeddingStore(embeddingRepo);

        assertEquals(2, store.criminalCount());
        List<CriminalEmbeddings> snap = store.snapshot();
        CriminalEmbeddings c1 = snap.stream().filter(c -> c.criminalId() == 1L).findFirst().orElseThrow();
        assertEquals(2, c1.embeddings().size());
    }

    @Test
    void snapshotIsImmutableAndDefensive() {
        insertRow(1L, "modelX", 1f, 0f);
        store.reloadFromDatabase();

        List<CriminalEmbeddings> snap = store.snapshot();
        CriminalEmbeddings ce = snap.get(0);

        assertThrows(UnsupportedOperationException.class,
                () -> ce.embeddings().add(new Embedding(new float[]{1f}, "modelX")));

        // Adding a new row then taking a new snapshot should reflect the change;
        // the previous snapshot must NOT mutate.
        int oldSize = ce.embeddings().size();
        insertRow(1L, "modelX", 0f, 1f);
        store.reloadForCriminal(1L);

        assertEquals(oldSize, ce.embeddings().size(), "prior snapshot must not mutate");
        assertEquals(2, store.snapshot().get(0).embeddings().size());
    }

    @Test
    void reloadForCriminalRemovesWhenDbEmpty() {
        insertRow(1L, "modelX", 1f, 0f);
        store.reloadFromDatabase();
        assertEquals(1, store.criminalCount());

        embeddingRepo.deleteByCriminalId(1L);
        store.reloadForCriminal(1L);

        assertEquals(0, store.criminalCount());
    }

    @Test
    void evictForCriminalDropsFromCache() {
        insertRow(1L, "modelX", 1f, 0f);
        insertRow(2L, "modelX", 0f, 1f);
        store.reloadFromDatabase();

        store.evictForCriminal(1L);
        assertEquals(1, store.criminalCount());
        Optional<CriminalEmbeddings> c1 = store.snapshot().stream()
                .filter(c -> c.criminalId() == 1L).findFirst();
        assertTrue(c1.isEmpty());
    }

    @Test
    void enrollmentPathVisibleWithoutManualRefresh() {
        // Regression test for F5: writing via the store must make readers see the update.
        assertEquals(0, store.criminalCount());

        insertRow(42L, "modelX", 0.5f, 0.5f);
        store.reloadForCriminal(42L);

        List<CriminalEmbeddings> snap = store.snapshot();
        assertEquals(1, snap.size());
        assertEquals(42L, snap.get(0).criminalId());
        assertEquals("modelX", snap.get(0).embeddings().get(0).modelId());
    }

    @Test
    void concurrentReloadAndSnapshotDoesNotThrow() throws Exception {
        for (int id = 1; id <= 20; id++) {
            insertRow(id, "modelX", id * 0.1f, 0.5f);
        }
        store.reloadFromDatabase();

        int iterations = 200;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        Thread writer = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < iterations; i++) {
                    store.reloadForCriminal(1L + (i % 20));
                }
            } catch (InterruptedException ignored) {
            } finally {
                done.countDown();
            }
        });

        final Throwable[] readerError = new Throwable[1];
        Thread reader = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < iterations; i++) {
                    for (CriminalEmbeddings ce : store.snapshot()) {
                        assertNotNull(ce.embeddings());
                    }
                }
            } catch (Throwable t) {
                readerError[0] = t;
            } finally {
                done.countDown();
            }
        });

        writer.setDaemon(true);
        reader.setDaemon(true);
        writer.start();
        reader.start();
        start.countDown();

        assertTrue(done.await(10, TimeUnit.SECONDS), "threads should complete");
        assertNull(readerError[0], "reader should not observe partial state or ConcurrentModificationException");
    }
}
