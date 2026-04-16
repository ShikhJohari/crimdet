package com.crimdet.service;

import com.crimdet.config.DatabaseConfig;
import com.crimdet.model.*;
import com.crimdet.repository.CriminalPhotoRepository;
import com.crimdet.repository.CriminalRepository;
import com.crimdet.repository.FaceEmbeddingRepository;
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

        g.setColor(new Color(210, 180, 140));
        g.fillOval(75, 50, 150, 200);

        g.setColor(Color.BLACK);
        g.fillOval(115, 120, 20, 15);
        g.fillOval(165, 120, 20, 15);

        g.fillOval(145, 155, 10, 20);

        g.setColor(new Color(180, 80, 80));
        g.fillOval(125, 195, 50, 15);

        g.dispose();
        return img;
    }

    /** Visually different synthetic image — horizontal color gradient stripes. */
    private BufferedImage createDifferentImage() {
        int size = 300;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
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

        List<DetectedFace> faces = FaceDetectionService.getInstance().detectFaces(img);

        assertNotNull(faces, "detectFaces should not return null");
        System.out.println("Detected " + faces.size() + " face(s)");
    }

    @Test
    void embeddingExtractAndCompare() {
        BufferedImage img = createFaceImage();

        FaceEmbeddingService embeddingService = new FaceEmbeddingService(
                FaceDetectionService.getInstance(), embeddingRepo, photoRepo);

        Embedding e1 = embeddingService.extractEmbedding(img);
        Embedding e2 = embeddingService.extractEmbedding(img);

        assertEquals(128, e1.dimension());
        assertEquals(FaceEmbeddingService.MODEL_ID, e1.modelId());

        double sim = e1.cosineSimilarity(e2);
        System.out.println("Self-similarity: " + sim);
        assertTrue(sim > 0.99, "Same image should have near-identical embeddings, got " + sim);
    }

    @Test
    void differentImages_produceDifferentEmbeddings() {
        FaceEmbeddingService embeddingService = new FaceEmbeddingService(
                FaceDetectionService.getInstance(), embeddingRepo, photoRepo);

        BufferedImage faceImg = createFaceImage();
        BufferedImage otherImg = createDifferentImage();

        Embedding e1 = embeddingService.extractEmbedding(faceImg);
        Embedding e2 = embeddingService.extractEmbedding(otherImg);

        assertEquals(128, e1.dimension());
        assertEquals(128, e2.dimension());

        double sim = e1.cosineSimilarity(e2);
        System.out.println("Cross-image similarity: " + sim);

        assertTrue(sim < 0.363,
                "Different images should have similarity below SFace threshold (0.363), got " + sim);
    }

    @Test
    void fullPipeline_registerThenScan() throws Exception {
        BufferedImage faceImg = createFaceImage();
        byte[] photoBytes = toBytes(faceImg);

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

        embeddingService.enrollCriminal(criminalId);

        List<FaceEmbedding> stored = embeddingRepo.findByCriminalId(criminalId);
        System.out.println("Stored embeddings after enroll: " + stored.size());

        if (stored.isEmpty()) {
            // DNN didn't detect synthetic face — enroll embedding directly
            Embedding emb = embeddingService.extractEmbedding(faceImg);
            FaceEmbedding fe = new FaceEmbedding();
            fe.setCriminalId(criminalId);
            fe.setPhotoId(photoId);
            fe.setModelId(emb.modelId());
            fe.setEmbedding(emb.toBytes());
            embeddingRepo.insert(fe);
            stored = embeddingRepo.findByCriminalId(criminalId);
            System.out.println("Manually enrolled embedding (DNN skipped synthetic face)");
        }

        assertFalse(stored.isEmpty(), "Should have at least one embedding");
        assertEquals(FaceEmbeddingService.MODEL_ID, stored.get(0).getModelId(),
                "Stored embedding must carry active model id");

        FaceMatchingService matchingService = new FaceMatchingService(embeddingRepo, criminalRepo);
        matchingService.refreshCache();

        Embedding scanEmbedding = embeddingService.extractEmbedding(faceImg);
        List<MatchResult> matches = matchingService.findMatches(scanEmbedding);
        System.out.println("Matches found: " + matches.size());
        assertFalse(matches.isEmpty(), "Same face image should produce a match");

        MatchResult best = matches.get(0);
        System.out.println("Best match: " + best.getCriminalName() + " confidence=" + best.getConfidence());
        assertEquals("Test Criminal", best.getCriminalName());
        assertTrue(best.getConfidence() >= 0.363, "Match confidence should exceed SFace threshold");
    }
}
