package com.crimdet.service;

import com.crimdet.config.DatabaseConfig;
import com.crimdet.model.Criminal;
import com.crimdet.model.CriminalEmbeddings;
import com.crimdet.model.Embedding;
import com.crimdet.model.MatchResult;
import com.crimdet.repository.CriminalRepository;

import java.util.*;

/**
 * Stateless matcher. Reads embeddings from the shared {@link EmbeddingStore} on
 * every call, so new enrollments and deletions are visible immediately without
 * any manual refresh step.
 */
public class FaceMatchingService {

    // SFace model author recommended cosine threshold (see sface.py in opencv_zoo)
    private static final double DEFAULT_THRESHOLD = 0.363;

    private final EmbeddingStore store;
    private final CriminalRepository criminalRepo;

    public FaceMatchingService() {
        var jdbi = DatabaseConfig.getInstance().getJdbi();
        this.store = EmbeddingStore.getInstance();
        this.criminalRepo = new CriminalRepository(jdbi);
    }

    public FaceMatchingService(EmbeddingStore store, CriminalRepository criminalRepo) {
        this.store = store;
        this.criminalRepo = criminalRepo;
    }

    public List<MatchResult> findMatches(Embedding query, double threshold) {
        List<MatchResult> matches = new ArrayList<>();

        for (CriminalEmbeddings ce : store.snapshot()) {
            double maxSim = Double.NEGATIVE_INFINITY;
            for (Embedding s : ce.embeddings()) {
                // Embedding.cosineSimilarity throws on model mismatch — that's the intended signal.
                double sim = query.cosineSimilarity(s);
                if (sim > maxSim) maxSim = sim;
            }

            if (maxSim >= threshold) {
                Optional<Criminal> criminal = criminalRepo.findById(ce.criminalId());
                if (criminal.isPresent()) {
                    Criminal c = criminal.get();
                    matches.add(new MatchResult(ce.criminalId(), c.getName(), maxSim, c.getStatus()));
                }
            }
        }

        matches.sort(Comparator.comparingDouble(MatchResult::getConfidence).reversed());
        return matches;
    }

    public List<MatchResult> findMatches(Embedding query) {
        return findMatches(query, DEFAULT_THRESHOLD);
    }
}
