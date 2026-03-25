package com.crimdet.service;

import com.crimdet.config.DatabaseConfig;
import com.crimdet.model.*;
import com.crimdet.repository.CriminalPhotoRepository;
import com.crimdet.repository.CriminalRepository;
import com.crimdet.repository.FaceEmbeddingRepository;
import com.crimdet.util.EmbeddingUtils;
import org.junit.jupiter.api.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the face pipeline: detection, embedding, matching.
 *
 * DNN SSD may not detect faces in synthetic drawn images, so embedding/matching
 * tests use extractEmbedding() directly (accepts any BufferedImage).
 * Detection is tested separately — just verify it loads and doesn't crash.
 */
class ScanPipelineTest {

    private static DatabaseConfig db;
    private CriminalRepository criminalRepo;
    private CriminalPhotoRepository photoRepo;
    private FaceEmbeddingRepository embeddingRepo;

    @BeforeAll
    static void initDb() {
        db = DatabaseConfig.create("jdbc:h2:mem:scantest;DB_CLOSE_DELAY=-1");
    }

    @BeforeEach
    void setUp() {
        var jdbi = db.getJdbi();
        criminalRepo = new CriminalRepository(jdbi);
        photoRepo = new CriminalPhotoRepository(jdbi);
        embeddingRepo = new FaceEmbeddingRepository(jdbi);
    }

    @AfterAll
    static void tearDown() {
        if (db != null) db.close();
    }

    /** Synthetic face-like image (oval + features). DNN may not detect it — that's OK. */
    private BufferedImage createFaceImage() {
        int size = 300;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, size, size);

        // Skin-colored face oval
        g.setColor(new Color(210, 180, 140));
        g.fillOval(75, 50, 150, 200);

        // Eyes
        g.setColor(Color.BLACK);
        g.fillOval(115, 120, 20, 15);
        g.fillOval(165, 120, 20, 15);

        // Nose
        g.fillOval(145, 155, 10, 20);

        // Mouth
        g.setColor(new Color(180, 80, 80));
        g.fillOval(125, 195, 50, 15);

        g.dispose();
        return img;
    }

    /** Visually different synthetic image — horizontal color gradient stripes. */
    private BufferedImage createDifferentImage() {
        int size = 300;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        // Fill with horizontal stripes of varying colors — pixel-level manipulation
        // ensures maximum visual difference from the face oval image
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                // Rotating RGB channels based on position creates a non-face pattern
                int r = (x * 17 + y * 31) % 256;
                int g = (x * 59 + y * 7) % 256;
                int b = (x * 43 + y * 13) % 256;
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    private byte[] toBytes(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    @Test
    void faceDetectionDoesNotHangOrCrash() {
        BufferedImage img = createFaceImage();

        // DNN may return 0 faces for synthetic images — that's fine
        List<DetectedFace> faces = FaceDetectionService.getInstance().detectFaces(img);

        assertNotNull(faces, "detectFaces should not return null");
        System.out.println("Detected " + faces.size() + " face(s)");
    }

    @Test
    void embeddingExtractAndCompare() {
        BufferedImage img = createFaceImage();

        FaceEmbeddingService embeddingService = new FaceEmbeddingService(
                FaceDetectionService.getInstance(), embeddingRepo, photoRepo);

        float[] embedding1 = embeddingService.extractEmbedding(img);
        float[] embedding2 = embeddingService.extractEmbedding(img);

        assertNotNull(embedding1);
        assertEquals(128, embedding1.length);

        // Same image should produce identical embeddings
        float sim = EmbeddingUtils.cosineSimilarity(embedding1, embedding2);
        System.out.println("Self-similarity: " + sim);
        assertTrue(sim > 0.99, "Same image should have near-identical embeddings, got " + sim);
    }

    @Test
    void differentImages_produceDifferentEmbeddings() {
        FaceEmbeddingService embeddingService = new FaceEmbeddingService(
                FaceDetectionService.getInstance(), embeddingRepo, photoRepo);

        BufferedImage faceImg = createFaceImage();
        BufferedImage otherImg = createDifferentImage();

        float[] embedding1 = embeddingService.extractEmbedding(faceImg);
        float[] embedding2 = embeddingService.extractEmbedding(otherImg);

        assertNotNull(embedding1);
        assertNotNull(embedding2);
        assertEquals(128, embedding1.length);
        assertEquals(128, embedding2.length);

        float sim = EmbeddingUtils.cosineSimilarity(embedding1, embedding2);
        System.out.println("Cross-image similarity: " + sim);

        // SFace match threshold is 0.363 — different images must be below it
        assertTrue(sim < 0.363,
                "Different images should have similarity below SFace threshold (0.363), got " + sim);
    }

    @Test
    void fullPipeline_registerThenScan() throws Exception {
        BufferedImage faceImg = createFaceImage();
        byte[] photoBytes = toBytes(faceImg);

        // Step 1: Register criminal
        Criminal c = new Criminal();
        c.setName("Test Criminal");
        c.setCrimeType("Theft");
        c.setStatus(CriminalStatus.WANTED);
        long criminalId = criminalRepo.insert(c);

        CriminalPhoto cp = new CriminalPhoto();
        cp.setCriminalId(criminalId);
        cp.setPhotoData(photoBytes);
        long photoId = photoRepo.insert(cp);
        assertTrue(photoId > 0);

        FaceEmbeddingService embeddingService = new FaceEmbeddingService(
                FaceDetectionService.getInstance(), embeddingRepo, photoRepo);

        // Step 2: Enroll — DNN may not detect face in synthetic image.
        // If enrollCriminal finds 0 faces, manually insert an embedding so we can test matching.
        embeddingService.enrollCriminal(criminalId);

        List<FaceEmbedding> stored = embeddingRepo.findByCriminalId(criminalId);
        System.out.println("Stored embeddings after enroll: " + stored.size());

        if (stored.isEmpty()) {
            // DNN didn't detect synthetic face — enroll embedding directly
            float[] embedding = embeddingService.extractEmbedding(faceImg);
            FaceEmbedding fe = new FaceEmbedding();
            fe.setCriminalId(criminalId);
            fe.setPhotoId(photoId);
            fe.setEmbedding(EmbeddingUtils.toBytes(embedding));
            embeddingRepo.insert(fe);
            stored = embeddingRepo.findByCriminalId(criminalId);
            System.out.println("Manually enrolled embedding (DNN skipped synthetic face)");
        }

        assertFalse(stored.isEmpty(), "Should have at least one embedding");

        // Step 3: Match — extract embedding from same image, find match
        FaceMatchingService matchingService = new FaceMatchingService(embeddingRepo, criminalRepo);
        matchingService.refreshCache();

        float[] scanEmbedding = embeddingService.extractEmbedding(faceImg);
        List<MatchResult> matches = matchingService.findMatches(scanEmbedding);
        System.out.println("Matches found: " + matches.size());
        assertFalse(matches.isEmpty(), "Same face image should produce a match");

        MatchResult best = matches.get(0);
        System.out.println("Best match: " + best.getCriminalName() + " confidence=" + best.getConfidence());
        assertEquals("Test Criminal", best.getCriminalName());
        assertTrue(best.getConfidence() >= 0.363, "Match confidence should exceed SFace threshold");
    }
}
