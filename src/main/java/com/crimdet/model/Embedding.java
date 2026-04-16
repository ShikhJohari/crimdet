package com.crimdet.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * A face embedding vector with its producing model's identity.
 *
 * Equality checks, similarity, and serialization all flow through this type so
 * embeddings from different models can never be silently compared.
 */
public record Embedding(float[] vector, String modelId) {

    public Embedding {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("embedding vector must be non-empty");
        }
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must be non-blank");
        }
        double norm = 0.0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        if (norm == 0.0) {
            throw new IllegalArgumentException("zero-norm embedding");
        }
    }

    public int dimension() {
        return vector.length;
    }

    /** Unclamped cosine similarity. Throws on model-id or dimension mismatch. */
    public double cosineSimilarity(Embedding other) {
        if (!modelId.equals(other.modelId)) {
            throw new IllegalArgumentException(
                    "cannot compare embeddings from different models: " + modelId + " vs " + other.modelId);
        }
        if (vector.length != other.vector.length) {
            throw new IllegalArgumentException(
                    "dimension mismatch: " + vector.length + " vs " + other.vector.length);
        }
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < vector.length; i++) {
            double a = vector[i];
            double b = other.vector[i];
            dot += a * b;
            na += a * a;
            nb += b * b;
        }
        // Both norms are > 0 (guaranteed by constructor).
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** Little-endian float32 serialization of the vector only. modelId is stored alongside, not in-band. */
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    public static Embedding fromBytes(byte[] data, String modelId) {
        if (data == null || data.length == 0 || data.length % Float.BYTES != 0) {
            throw new IllegalArgumentException("invalid embedding byte length: "
                    + (data == null ? "null" : data.length));
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        float[] vec = new float[data.length / Float.BYTES];
        for (int i = 0; i < vec.length; i++) {
            vec[i] = buffer.getFloat();
        }
        return new Embedding(vec, modelId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Embedding e)) return false;
        return modelId.equals(e.modelId) && Arrays.equals(vector, e.vector);
    }

    @Override
    public int hashCode() {
        return 31 * modelId.hashCode() + Arrays.hashCode(vector);
    }

    @Override
    public String toString() {
        return "Embedding[modelId=" + modelId + ", dim=" + vector.length + "]";
    }
}
