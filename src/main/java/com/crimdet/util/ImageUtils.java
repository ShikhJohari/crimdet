package com.crimdet.util;

import javafx.scene.image.Image;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class ImageUtils {

    private static final int MAX_PHOTO_WIDTH = 800;
    private static final int MAX_PHOTO_HEIGHT = 800;

    private ImageUtils() {}

    public static byte[] resizeIfNeeded(byte[] imageData) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageData));
        if (img == null) throw new IOException("Invalid image data");

        if (img.getWidth() <= MAX_PHOTO_WIDTH && img.getHeight() <= MAX_PHOTO_HEIGHT) {
            return imageData;
        }

        double scale = Math.min(
                (double) MAX_PHOTO_WIDTH / img.getWidth(),
                (double) MAX_PHOTO_HEIGHT / img.getHeight()
        );
        int newW = (int) (img.getWidth() * scale);
        int newH = (int) (img.getHeight() * scale);

        BufferedImage resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, newW, newH, null);
        g.dispose();

        return toBytes(resized, "jpg");
    }

    public static byte[] toBytes(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, format, baos);
        return baos.toByteArray();
    }

    public static Image toFxImage(byte[] data) {
        return new Image(new ByteArrayInputStream(data));
    }

    public static Image toFxImage(byte[] data, double fitWidth, double fitHeight) {
        return new Image(new ByteArrayInputStream(data), fitWidth, fitHeight, true, true);
    }
}
