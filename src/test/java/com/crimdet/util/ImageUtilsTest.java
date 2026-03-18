package com.crimdet.util;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ImageUtilsTest {

    // --- resizeIfNeeded ---

    @Test
    void smallImageNotResized() throws IOException {
        byte[] small = createTestImage(200, 200, "png");
        byte[] result = ImageUtils.resizeIfNeeded(small);
        // Should return same bytes (no resize needed)
        assertSame(small, result);
    }

    @Test
    void exactBoundaryNotResized() throws IOException {
        byte[] exact = createTestImage(800, 800, "png");
        byte[] result = ImageUtils.resizeIfNeeded(exact);
        assertSame(exact, result);
    }

    @Test
    void wideImageResized() throws IOException {
        byte[] wide = createTestImage(1600, 400, "png");
        byte[] result = ImageUtils.resizeIfNeeded(wide);
        assertNotSame(wide, result);

        BufferedImage resized = ImageIO.read(new ByteArrayInputStream(result));
        // Scale = 800/1600 = 0.5 → 800x200
        assertTrue(resized.getWidth() <= 800);
        assertTrue(resized.getHeight() <= 800);
        assertEquals(800, resized.getWidth());
        assertEquals(200, resized.getHeight());
    }

    @Test
    void tallImageResized() throws IOException {
        byte[] tall = createTestImage(400, 1600, "png");
        byte[] result = ImageUtils.resizeIfNeeded(tall);
        assertNotSame(tall, result);

        BufferedImage resized = ImageIO.read(new ByteArrayInputStream(result));
        // Scale = 800/1600 = 0.5 → 200x800
        assertTrue(resized.getWidth() <= 800);
        assertTrue(resized.getHeight() <= 800);
        assertEquals(200, resized.getWidth());
        assertEquals(800, resized.getHeight());
    }

    @Test
    void largeSquareImageResized() throws IOException {
        byte[] large = createTestImage(2000, 2000, "png");
        byte[] result = ImageUtils.resizeIfNeeded(large);
        assertNotSame(large, result);

        BufferedImage resized = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(800, resized.getWidth());
        assertEquals(800, resized.getHeight());
    }

    @Test
    void slightlyOverBoundaryResized() throws IOException {
        byte[] img = createTestImage(801, 800, "png");
        byte[] result = ImageUtils.resizeIfNeeded(img);
        assertNotSame(img, result);

        BufferedImage resized = ImageIO.read(new ByteArrayInputStream(result));
        assertTrue(resized.getWidth() <= 800);
    }

    @Test
    void invalidImageDataThrows() {
        byte[] garbage = new byte[]{0, 1, 2, 3, 4};
        assertThrows(IOException.class, () -> ImageUtils.resizeIfNeeded(garbage));
    }

    @Test
    void emptyDataThrows() {
        assertThrows(IOException.class, () -> ImageUtils.resizeIfNeeded(new byte[0]));
    }

    // --- toBytes ---

    @Test
    void toBytesProducesValidImage() throws IOException {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        byte[] bytes = ImageUtils.toBytes(img, "png");
        assertTrue(bytes.length > 0);

        BufferedImage roundtrip = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(roundtrip);
        assertEquals(100, roundtrip.getWidth());
        assertEquals(100, roundtrip.getHeight());
    }

    @Test
    void toBytesJpgFormat() throws IOException {
        BufferedImage img = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        byte[] bytes = ImageUtils.toBytes(img, "jpg");
        assertTrue(bytes.length > 0);

        BufferedImage roundtrip = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(roundtrip);
    }

    // --- Helpers ---

    private byte[] createTestImage(int width, int height, String format) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, format, baos);
        return baos.toByteArray();
    }
}
