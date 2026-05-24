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
    void addInkDoesNotWriteBlackAlphaOntoTransparentCanvas() throws Exception {
        BufferedImage canvas = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        canvas.setRGB(0, 0, new Color(0, 0, 0, 0).getRGB());
        canvas.setRGB(1, 0, new Color(100, 80, 60, 255).getRGB());

        BufferedImage foreground = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        foreground.setRGB(0, 0, new Color(0, 0, 0, 255).getRGB());
        foreground.setRGB(1, 0, new Color(30, 40, 50, 255).getRGB());

        applyAddPinBlending(canvas, foreground, true);

        Color transparentPixel = new Color(canvas.getRGB(0, 0), true);
        assertEquals(0, transparentPixel.getAlpha());

        Color blendedPixel = new Color(canvas.getRGB(1, 0), true);
        assertEquals(130, blendedPixel.getRed());
        assertEquals(120, blendedPixel.getGreen());
        assertEquals(110, blendedPixel.getBlue());
        assertEquals(255, blendedPixel.getAlpha());
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
}
