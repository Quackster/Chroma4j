package com.quackster.chroma.util;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImageUtilTest {

    @Test
    void trimBitmapPreservesCSharpAllTransparentCropShape() {
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);

        BufferedImage trimmed = ImageUtil.trimBitmap(source);

        assertEquals(1, trimmed.getWidth());
        assertEquals(1, trimmed.getHeight());
        assertEquals(0, new Color(trimmed.getRGB(0, 0), true).getAlpha());
    }

    @Test
    void trimBitmapQuantizesPartialAlphaLikeSystemDrawingCrop() {
        BufferedImage source = new BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB);
        Color trim = new Color(0, 0, 0, 0);
        int sourceArgb = new Color(200, 40, 120, 128).getRGB();
        source.setRGB(0, 0, sourceArgb);

        BufferedImage trimmed = ImageUtil.trimBitmap(source, trim);
        Color pixel = new Color(trimmed.getRGB(0, 0), true);

        assertEquals(1, trimmed.getWidth());
        assertEquals(1, trimmed.getHeight());
        assertEquals(199, pixel.getRed());
        assertEquals(39, pixel.getGreen());
        assertEquals(119, pixel.getBlue());
        assertEquals(128, pixel.getAlpha());
    }
}
