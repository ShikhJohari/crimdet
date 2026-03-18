package com.crimdet.service;

import com.crimdet.config.DatabaseConfig;
import com.crimdet.model.CriminalPhoto;
import com.crimdet.model.DetectedFace;
import com.crimdet.model.FaceEmbedding;
import com.crimdet.repository.CriminalPhotoRepository;
import com.crimdet.repository.FaceEmbeddingRepository;
import com.crimdet.util.EmbeddingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

public class FaceEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(FaceEmbeddingService.class);
    private static final int EMBED_SIZE = 64;
    private static final int EMBED_LENGTH = EMBED_SIZE * EMBED_SIZE; // 4096

    private final FaceDetectionService faceDetectionService;
    private final FaceEmbeddingRepository embeddingRepo;
    private final CriminalPhotoRepository photoRepo;

    public FaceEmbeddingService() {
        var jdbi = DatabaseConfig.getInstance().getJdbi();
        this.faceDetectionService = FaceDetectionService.getInstance();
        this.embeddingRepo = new FaceEmbeddingRepository(jdbi);
        this.photoRepo = new CriminalPhotoRepository(jdbi);
    }

    public FaceEmbeddingService(FaceDetectionService faceDetectionService,
                                FaceEmbeddingRepository embeddingRepo,
                                CriminalPhotoRepository photoRepo) {
        this.faceDetectionService = faceDetectionService;
        this.embeddingRepo = embeddingRepo;
        this.photoRepo = photoRepo;
    }

    public float[] extractEmbedding(BufferedImage faceImage) {
        // Resize to 64x64 grayscale
        BufferedImage gray = new BufferedImage(EMBED_SIZE, EMBED_SIZE, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(faceImage, 0, 0, EMBED_SIZE, EMBED_SIZE, null);
        g.dispose();

        // Extract pixels and normalize to [0, 1]
        float[] embedding = new float[EMBED_LENGTH];
        for (int y = 0; y < EMBED_SIZE; y++) {
            for (int x = 0; x < EMBED_SIZE; x++) {
                int pixel = gray.getRaster().getSample(x, y, 0);
                embedding[y * EMBED_SIZE + x] = pixel / 255.0f;
            }
        }

        // L2-normalize
        float norm = 0f;
        for (float v : embedding) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0f) {
            for (int i = 0; i < embedding.length; i++) {
                embedding[i] /= norm;
            }
        }

        return embedding;
    }

    public void enrollCriminal(long criminalId) {
        // Remove existing embeddings for this criminal
        embeddingRepo.deleteByCriminalId(criminalId);

        List<CriminalPhoto> photos = photoRepo.findByCriminalId(criminalId);
        int enrolled = 0;

        for (CriminalPhoto photo : photos) {
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(photo.getPhotoData()));
                if (image == null) {
                    log.warn("Could not read photo id={}", photo.getId());
                    continue;
                }

                List<DetectedFace> faces = faceDetectionService.detectFaces(image);
                if (faces.isEmpty()) {
                    log.warn("No face detected in photo id={}", photo.getId());
                    continue;
                }

                // Use the first (largest) detected face
                DetectedFace face = faces.get(0);
                float[] embedding = extractEmbedding(face.getCroppedFace());

                FaceEmbedding fe = new FaceEmbedding();
                fe.setCriminalId(criminalId);
                fe.setPhotoId(photo.getId());
                fe.setEmbedding(EmbeddingUtils.toBytes(embedding));
                embeddingRepo.insert(fe);
                enrolled++;
            } catch (IOException e) {
                log.error("Failed to process photo id={}", photo.getId(), e);
            }
        }

        log.info("Enrolled criminal id={}: {}/{} photos processed", criminalId, enrolled, photos.size());
    }
}
