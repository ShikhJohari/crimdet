package com.crimdet.service;

import com.crimdet.config.DatabaseConfig;
import com.crimdet.model.CriminalEmbeddings;
import com.crimdet.model.Embedding;
import com.crimdet.model.FaceEmbedding;
import com.crimdet.repository.FaceEmbeddingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for in-memory face embeddings. Backed by
 * {@link FaceEmbeddingRepository}; anyone who persists embeddings must notify the
 * store via {@link #reloadForCriminal(long)} or {@link #evictForCriminal(long)} so
 * readers (matching, overlays) never see stale caches.
 *
 * Thread-safety: all read and write methods are guarded by the same intrinsic lock.
 * {@link #snapshot()} returns a defensive copy so iterators never run while another
 * thread mutates the map.
 */
public final class EmbeddingStore {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingStore.class);

    private static volatile EmbeddingStore instance;

    private final FaceEmbeddingRepository repo;
    private final Map<Long, List<Embedding>> cache = new HashMap<>();

    public static EmbeddingStore getInstance() {
        EmbeddingStore local = instance;
        if (local == null) {
            synchronized (EmbeddingStore.class) {
                local = instance;
                if (local == null) {
                    FaceEmbeddingRepository repo =
                            new FaceEmbeddingRepository(DatabaseConfig.getInstance().getJdbi());
                    local = new EmbeddingStore(repo);
                    instance = local;
                }
            }
        }
        return local;
    }

    /** Test constructor; do NOT call from production code. */
    EmbeddingStore(FaceEmbeddingRepository repo) {
        this.repo = repo;
        reloadFromDatabase();
    }

    /** Replaces the {@link #getInstance()} singleton; test hook only. */
    static synchronized void setInstanceForTesting(EmbeddingStore store) {
        instance = store;
    }

    /** Returns defensive copies of all cached embeddings, one entry per criminal. */
    public synchronized List<CriminalEmbeddings> snapshot() {
        List<CriminalEmbeddings> list = new ArrayList<>(cache.size());
        for (Map.Entry<Long, List<Embedding>> e : cache.entrySet()) {
            list.add(new CriminalEmbeddings(e.getKey(), e.getValue()));
        }
        return list;
    }

    /** Re-reads the given criminal's embeddings from the DB. Call after enrollment. */
    public synchronized void reloadForCriminal(long criminalId) {
        List<FaceEmbedding> rows = repo.findByCriminalId(criminalId);
        if (rows.isEmpty()) {
            cache.remove(criminalId);
            log.debug("reloadForCriminal({}): no embeddings, evicted", criminalId);
            return;
        }
        List<Embedding> loaded = new ArrayList<>(rows.size());
        for (FaceEmbedding fe : rows) {
            loaded.add(Embedding.fromBytes(fe.getEmbedding(), fe.getModelId()));
        }
        cache.put(criminalId, loaded);
        log.debug("reloadForCriminal({}): {} embeddings", criminalId, loaded.size());
    }

    /** Drops a criminal's embeddings from the cache. DB rows are expected to be gone already (cascade delete). */
    public synchronized void evictForCriminal(long criminalId) {
        cache.remove(criminalId);
        log.debug("evictForCriminal({}): removed", criminalId);
    }

    /** Full reload from DB. Used at startup and as an escape hatch after bulk migrations. */
    public synchronized void reloadFromDatabase() {
        cache.clear();
        List<FaceEmbedding> all = repo.findAll();
        for (FaceEmbedding fe : all) {
            Embedding emb = Embedding.fromBytes(fe.getEmbedding(), fe.getModelId());
            cache.computeIfAbsent(fe.getCriminalId(), k -> new ArrayList<>()).add(emb);
        }
        log.info("EmbeddingStore loaded: {} criminals, {} embeddings", cache.size(), all.size());
    }

    /** Observable size for tests / diagnostics. */
    public synchronized int criminalCount() {
        return cache.size();
    }
}
