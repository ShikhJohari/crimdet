package com.crimdet.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingTest {

    private static final String MODEL_A = "modelA";
    private static final String MODEL_B = "modelB";

    private Embedding make(String modelId, float... values) {
        return new Embedding(values, modelId);
    }

    @Test
    void rejectsEmptyVector() {
        assertThrows(IllegalArgumentException.class, () -> new Embedding(new float[0], MODEL_A));
        assertThrows(IllegalArgumentException.class, () -> new Embedding(null, MODEL_A));
    }

    @Test
    void rejectsZeroNorm() {
        assertThrows(IllegalArgumentException.class, () -> new Embedding(new float[]{0f, 0f, 0f}, MODEL_A));
    }

    @Test
    void rejectsBlankModelId() {
        assertThrows(IllegalArgumentException.class, () -> make(null, 1, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> make("", 1, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> make("   ", 1, 2, 3));
    }

    @Test
    void dimensionReflectsVectorLength() {
        assertEquals(3, make(MODEL_A, 1, 2, 3).dimension());
        float[] buf = new float[128];
        buf[0] = 1f;
        assertEquals(128, new Embedding(buf, MODEL_A).dimension());
    }

    @Test
    void crossModelComparisonThrows() {
        Embedding a = make(MODEL_A, 1, 0);
        Embedding b = make(MODEL_B, 1, 0);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> a.cosineSimilarity(b));
        assertTrue(ex.getMessage().contains("different models"));
    }

    @Test
    void dimensionMismatchThrows() {
        Embedding a = make(MODEL_A, 1, 0, 0);
        Embedding b = make(MODEL_A, 1, 0);
        assertThrows(IllegalArgumentException.class, () -> a.cosineSimilarity(b));
    }

    @Test
    void cosineSimilarityIdentical() {
        Embedding a = make(MODEL_A, 1, 2, 3);
        Embedding b = make(MODEL_A, 1, 2, 3);
        assertEquals(1.0, a.cosineSimilarity(b), 1e-6);
    }

    @Test
    void cosineSimilarityOrthogonal() {
        Embedding a = make(MODEL_A, 1, 0);
        Embedding b = make(MODEL_A, 0, 1);
        assertEquals(0.0, a.cosineSimilarity(b), 1e-6);
    }

    @Test
    void cosineSimilarityAntipodalPreserved() {
        // Old EmbeddingUtils clamped negative similarity to 0. Embedding must not.
        Embedding a = make(MODEL_A, 1, 0);
        Embedding b = make(MODEL_A, -1, 0);
        assertEquals(-1.0, a.cosineSimilarity(b), 1e-6);
    }

    @Test
    void roundTripBytes() {
        Embedding original = make(MODEL_A, 1.25f, -3.5f, 0.125f, 7.0f);
        byte[] bytes = original.toBytes();
        Embedding back = Embedding.fromBytes(bytes, MODEL_A);
        assertEquals(original, back);
        assertEquals(original.hashCode(), back.hashCode());
    }

    @Test
    void fromBytesRejectsInvalidLength() {
        assertThrows(IllegalArgumentException.class, () -> Embedding.fromBytes(new byte[]{1, 2, 3}, MODEL_A));
        assertThrows(IllegalArgumentException.class, () -> Embedding.fromBytes(new byte[0], MODEL_A));
        assertThrows(IllegalArgumentException.class, () -> Embedding.fromBytes(null, MODEL_A));
    }

    @Test
    void equalityRespectsModelId() {
        Embedding a = make(MODEL_A, 1, 2);
        Embedding b = make(MODEL_B, 1, 2);
        assertNotEquals(a, b);
    }
}
