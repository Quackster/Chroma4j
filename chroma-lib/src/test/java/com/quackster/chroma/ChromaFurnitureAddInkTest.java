package com.quackster.chroma;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.List;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChromaFurnitureAddInkTest {

    @Test
    void addInkKeepsBlackTransparentButShowsColorOnTransparentCanvas() throws Exception {
        BufferedImage canvas = new BufferedImage(3, 1, BufferedImage.TYPE_INT_ARGB);
        canvas.setRGB(0, 0, new Color(0, 0, 0, 0).getRGB());
        canvas.setRGB(1, 0, new Color(100, 80, 60, 255).getRGB());
        canvas.setRGB(2, 0, new Color(0, 0, 0, 0).getRGB());

        BufferedImage foreground = new BufferedImage(3, 1, BufferedImage.TYPE_INT_ARGB);
        foreground.setRGB(0, 0, new Color(0, 0, 0, 255).getRGB());
        foreground.setRGB(1, 0, new Color(30, 40, 50, 255).getRGB());
        foreground.setRGB(2, 0, new Color(200, 100, 50, 128).getRGB());

        applyAddPinBlending(canvas, foreground, true);

        Color transparentPixel = new Color(canvas.getRGB(0, 0), true);
        assertEquals(0, transparentPixel.getAlpha());

        Color blendedPixel = new Color(canvas.getRGB(1, 0), true);
        assertEquals(130, blendedPixel.getRed());
        assertEquals(120, blendedPixel.getGreen());
        assertEquals(110, blendedPixel.getBlue());
        assertEquals(255, blendedPixel.getAlpha());

        Color transparentAddPixel = new Color(canvas.getRGB(2, 0), true);
        assertEquals(200, transparentAddPixel.getRed());
        assertEquals(100, transparentAddPixel.getGreen());
        assertEquals(50, transparentAddPixel.getBlue());
        assertEquals(128, transparentAddPixel.getAlpha());
    }

    @Test
    void addInkKeepsSourceAlphaBehaviorOnOpaqueCanvas() throws Exception {
        BufferedImage canvas = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        canvas.setRGB(0, 0, new Color(100, 80, 60, 255).getRGB());

        BufferedImage foreground = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        foreground.setRGB(0, 0, new Color(30, 40, 50, 128).getRGB());

        applyAddPinBlending(canvas, foreground, false);

        Color blendedPixel = new Color(canvas.getRGB(0, 0), true);
        assertEquals(115, blendedPixel.getRed());
        assertEquals(100, blendedPixel.getGreen());
        assertEquals(85, blendedPixel.getBlue());
        assertEquals(255, blendedPixel.getAlpha());
    }

    @Test
    void gifEncoderDoesNotTreatOpaqueBlackAsTransparent() throws Exception {
        BufferedImage frame = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        frame.setRGB(0, 0, new Color(0, 0, 0, 0).getRGB());
        frame.setRGB(1, 0, new Color(0, 0, 0, 255).getRGB());

        byte[] gif = SimpleGifEncoder.encode(List.of(frame), 120);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(gif));

        Color transparentPixel = new Color(decoded.getRGB(0, 0), true);
        assertEquals(0, transparentPixel.getAlpha());

        Color blackPixel = new Color(decoded.getRGB(1, 0), true);
        assertEquals(255, blackPixel.getAlpha());
        assertTrue(blackPixel.getRed() <= 64);
        assertTrue(blackPixel.getGreen() <= 64);
        assertTrue(blackPixel.getBlue() <= 64);
    }

    @Test
    void apngEncoderWritesAnimatedPngChunks() {
        BufferedImage first = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        BufferedImage second = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        first.setRGB(0, 0, new Color(255, 0, 0, 255).getRGB());
        second.setRGB(0, 0, new Color(0, 255, 0, 255).getRGB());

        byte[] apng = SimpleApngEncoder.encode(List.of(first, second), 120, false);

        assertEquals((byte) 0x89, apng[0]);
        assertEquals(0x50, apng[1]);
        assertEquals(0x4e, apng[2]);
        assertEquals(0x47, apng[3]);
        assertTrue(containsAscii(apng, "acTL"));
        assertTrue(containsAscii(apng, "fcTL"));
        assertTrue(containsAscii(apng, "IDAT"));
        assertTrue(containsAscii(apng, "fdAT"));
    }

    private static void applyAddPinBlending(
        BufferedImage canvas,
        BufferedImage foreground,
        boolean preserveDestinationAlpha) throws Exception {

        ChromaFurniture furniture = new ChromaFurniture("test.swf", false, 0, 0);
        Method method = ChromaFurniture.class.getDeclaredMethod(
            "applyAddPinBlending",
            BufferedImage.class,
            BufferedImage.class,
            int.class,
            int.class,
            boolean.class);
        method.setAccessible(true);
        method.invoke(furniture, canvas, foreground, 0, 0, preserveDestinationAlpha);
    }

    private static boolean containsAscii(byte[] bytes, String needle) {
        byte[] needleBytes = needle.getBytes();
        for (int i = 0; i <= bytes.length - needleBytes.length; i++) {
            boolean matches = true;
            for (int j = 0; j < needleBytes.length; j++) {
                if (bytes[i + j] != needleBytes[j]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }
}
