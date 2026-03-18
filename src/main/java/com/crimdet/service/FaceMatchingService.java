package com.crimdet.service;

import com.crimdet.config.DatabaseConfig;
import com.crimdet.model.Criminal;
import com.crimdet.model.FaceEmbedding;
import com.crimdet.model.MatchResult;
import com.crimdet.repository.CriminalRepository;
import com.crimdet.repository.FaceEmbeddingRepository;
import com.crimdet.util.EmbeddingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class FaceMatchingService {

    private static final Logger log = LoggerFactory.getLogger(FaceMatchingService.class);
    private static final double DEFAULT_THRESHOLD = 0.6;

    private final FaceEmbeddingRepository embeddingRepo;
    private final CriminalRepository criminalRepo;
    private final Map<Long, List<float[]>> embeddingCache = new HashMap<>();

    public FaceMatchingService() {
        var jdbi = DatabaseConfig.getInstance().getJdbi();
        this.embeddingRepo = new FaceEmbeddingRepository(jdbi);
        this.criminalRepo = new CriminalRepository(jdbi);
    }

    public FaceMatchingService(FaceEmbeddingRepository embeddingRepo, CriminalRepository criminalRepo) {
        this.embeddingRepo = embeddingRepo;
        this.criminalRepo = criminalRepo;
    }

    public synchronized void refreshCache() {
        embeddingCache.clear();
        List<FaceEmbedding> all = embeddingRepo.findAll();
        for (FaceEmbedding fe : all) {
            float[] vec = EmbeddingUtils.toFloats(fe.getEmbedding());
            embeddingCache.computeIfAbsent(fe.getCriminalId(), k -> new ArrayList<>()).add(vec);
        }
        log.info("Embedding cache refreshed: {} criminals, {} embeddings",
                embeddingCache.size(), all.size());
    }

    public List<MatchResult> findMatches(float[] queryEmbedding, double threshold) {
        List<MatchResult> matches = new ArrayList<>();

        for (Map.Entry<Long, List<float[]>> entry : embeddingCache.entrySet()) {
            long criminalId = entry.getKey();
            List<float[]> embeddings = entry.getValue();

            // Use max similarity across all embeddings for this criminal
            double maxSim = 0.0;
            for (float[] stored : embeddings) {
                double sim = EmbeddingUtils.cosineSimilarity(queryEmbedding, stored);
                maxSim = Math.max(maxSim, sim);
            }

            if (maxSim >= threshold) {
                Optional<Criminal> criminal = criminalRepo.findById(criminalId);
                if (criminal.isPresent()) {
                    Criminal c = criminal.get();
                    matches.add(new MatchResult(criminalId, c.getName(), maxSim, c.getStatus()));
                }
            }
        }

        // Sort by confidence descending
        matches.sort(Comparator.comparingDouble(MatchResult::getConfidence).reversed());
        return matches;
    }

    public List<MatchResult> findMatches(float[] queryEmbedding) {
        return findMatches(queryEmbedding, DEFAULT_THRESHOLD);
    }
}
