package com.crimdet.model;

import java.util.List;

/**
 * Snapshot of all embeddings belonging to a single criminal. The {@code embeddings}
 * list is an immutable defensive copy taken at snapshot time.
 */
public record CriminalEmbeddings(long criminalId, List<Embedding> embeddings) {

    public CriminalEmbeddings {
        embeddings = List.copyOf(embeddings);
    }
}
