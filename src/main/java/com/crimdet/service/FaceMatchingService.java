package com.crimdet.service;

import com.crimdet.config.DatabaseConfig;
import com.crimdet.model.Criminal;
import com.crimdet.model.Embedding;
import com.crimdet.model.FaceEmbedding;
import com.crimdet.model.MatchResult;
import com.crimdet.repository.CriminalRepository;
import com.crimdet.repository.FaceEmbeddingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class FaceMatchingService {

    private static final Logger log = LoggerFactory.getLogger(FaceMatchingService.class);
    // SFace model author recommended cosine threshold (see sface.py in opencv_zoo)
    private static final double DEFAULT_THRESHOLD = 0.363;

    private final FaceEmbeddingRepository embeddingRepo;
    private final CriminalRepository criminalRepo;
    private final Map<Long, List<Embedding>> embeddingCache = new HashMap<>();

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
            Embedding emb = Embedding.fromBytes(fe.getEmbedding(), fe.getModelId());
            embeddingCache.computeIfAbsent(fe.getCriminalId(), k -> new ArrayList<>()).add(emb);
        }
        log.info("Embedding cache refreshed: {} criminals, {} embeddings",
                embeddingCache.size(), all.size());
    }

    public synchronized List<MatchResult> findMatches(Embedding query, double threshold) {
        List<MatchResult> matches = new ArrayList<>();

        for (Map.Entry<Long, List<Embedding>> entry : embeddingCache.entrySet()) {
            long criminalId = entry.getKey();
            List<Embedding> stored = entry.getValue();

            double maxSim = Double.NEGATIVE_INFINITY;
            for (Embedding s : stored) {
                // Embedding.cosineSimilarity throws on model mismatch — that's the intended signal.
                double sim = query.cosineSimilarity(s);
                if (sim > maxSim) maxSim = sim;
            }

            if (maxSim >= threshold) {
                Optional<Criminal> criminal = criminalRepo.findById(criminalId);
                if (criminal.isPresent()) {
                    Criminal c = criminal.get();
                    matches.add(new MatchResult(criminalId, c.getName(), maxSim, c.getStatus()));
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
