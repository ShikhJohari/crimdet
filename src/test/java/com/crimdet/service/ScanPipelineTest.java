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
 * End-to-end test: register suspect with photo → enroll embeddings → scan same image → verify match.
 * Reproduces the "stuck scanning" bug.
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

    /** Create a synthetic image with a face-like pattern (oval + features) that Haar cascade can detect. */
    private BufferedImage createFaceImage() {
        int size = 300;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Light background
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, size, size);

        // Skin-colored face oval
        g.setColor(new Color(210, 180, 140));
        g.fillOval(75, 50, 150, 200);

        // Eyes (dark)
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

    private byte[] toBytes(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    @Test
    void faceDetectionDoesNotHangOrCrash() {
        BufferedImage img = createFaceImage();

        // This is the call that may hang/crash in the live monitor
        List<DetectedFace> faces = FaceDetectionService.getInstance().detectFaces(img);

        assertNotNull(faces, "detectFaces should not return null");
        System.out.println("Detected " + faces.size() + " face(s)");
    }

    @Test
    void embeddingExtractAndCompare() {
        BufferedImage img = createFaceImage();

        FaceEmbeddingService embeddingService = new FaceEmbeddingService(
                FaceDetectionService.getInstance(), embeddingRepo, photoRepo);

        // Extract embedding from the image directly (simulating scan)
        float[] embedding1 = embeddingService.extractEmbedding(img);
        float[] embedding2 = embeddingService.extractEmbedding(img);

        assertNotNull(embedding1);
        assertEquals(128, embedding1.length);

        // Same image should produce identical embeddings
        float sim = EmbeddingUtils.cosineSimilarity(embedding1, embedding2);
        System.out.println("Self-similarity: " + sim);
        assertTrue(sim > 0.99, "Same image should have near-identical embeddings");
    }

    @Test
    void fullPipeline_registerThenScan() throws Exception {
        BufferedImage faceImg = createFaceImage();
        byte[] photoBytes = toBytes(faceImg);

        // Step 1: Register criminal (what dashboard "Register Suspect" does)
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

        // Step 2: Enroll (what CriminalFormController.onSave does after saving)
        FaceEmbeddingService embeddingService = new FaceEmbeddingService(
                FaceDetectionService.getInstance(), embeddingRepo, photoRepo);
        embeddingService.enrollCriminal(criminalId);

        // Verify embeddings were stored
        List<FaceEmbedding> stored = embeddingRepo.findByCriminalId(criminalId);
        System.out.println("Stored embeddings: " + stored.size());
        assertFalse(stored.isEmpty(), "Enrollment should store at least one embedding");

        // Step 3: Scan same image (what LiveMonitorController.onScan does)
        FaceMatchingService matchingService = new FaceMatchingService(embeddingRepo, criminalRepo);
        matchingService.refreshCache();

        // Detect faces in scan image — use PNG round-tripped image (matches how live monitor loads files)
        BufferedImage scanImage = ImageIO.read(new java.io.ByteArrayInputStream(photoBytes));
        List<DetectedFace> faces = FaceDetectionService.getInstance().detectFaces(scanImage);
        System.out.println("Scan detected " + faces.size() + " face(s)");
        assertFalse(faces.isEmpty(), "Face detection should find at least one face in scan image");

        float[] scanEmbedding = embeddingService.extractEmbedding(faces.get(0).getCroppedFace());
        List<MatchResult> matches = matchingService.findMatches(scanEmbedding);
        System.out.println("Matches found: " + matches.size());
        assertFalse(matches.isEmpty(), "Same face should produce a match");

        MatchResult best = matches.get(0);
        System.out.println("Best match: " + best.getCriminalName() + " confidence=" + best.getConfidence());
        assertEquals("Test Criminal", best.getCriminalName());
        assertTrue(best.getConfidence() >= 0.6, "Match confidence should exceed threshold");
    }
}
