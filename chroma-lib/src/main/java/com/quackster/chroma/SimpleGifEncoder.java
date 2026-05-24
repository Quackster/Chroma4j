package com.quackster.chroma;

import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SimpleGifEncoder {
    private SimpleGifEncoder() {
    }

    static byte[] encode(List<BufferedImage> frames, int delayMs) {
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("frames");
        }

        int width = frames.get(0).getWidth();
        int height = frames.get(0).getHeight();
        byte[][] rgbaFrames = new byte[frames.size()][];
        for (int i = 0; i < frames.size(); i++) {
            BufferedImage frame = frames.get(i);
            if (frame.getWidth() != width || frame.getHeight() != height) {
                throw new IllegalArgumentException("All GIF frames must have the same size");
            }
            rgbaFrames[i] = rgba(frame);
        }

        Palette palette = Palette.fromFrames(rgbaFrames);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageWriter writer = gifWriter();
            try (ImageOutputStream stream = ImageIO.createImageOutputStream(output)) {
                writer.setOutput(stream);
                writer.prepareWriteSequence(null);
                for (int i = 0; i < rgbaFrames.length; i++) {
                    BufferedImage frame = indexedImage(width, height, rgbaFrames[i], palette);
                    IIOMetadata metadata = metadata(writer, frame, delayMs, i == 0);
                    writer.writeToSequence(new javax.imageio.IIOImage(frame, null, metadata), null);
                }
                writer.endWriteSequence();
            } finally {
                writer.dispose();
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static ImageWriter gifWriter() {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersBySuffix("gif");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No GIF ImageIO writer is available");
        }
        return writers.next();
    }

    private static BufferedImage indexedImage(int width, int height, byte[] rgba, Palette palette) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, palette.colorModel());
        WritableRaster raster = image.getRaster();
        byte[] indexed = palette.index(rgba);
        for (int y = 0, offset = 0; y < height; y++) {
            for (int x = 0; x < width; x++, offset++) {
                raster.setSample(x, y, 0, indexed[offset] & 255);
            }
        }
        return image;
    }

    private static IIOMetadata metadata(ImageWriter writer, BufferedImage image, int delayMs, boolean firstFrame) throws IOException {
        ImageTypeSpecifier type = ImageTypeSpecifier.createFromRenderedImage(image);
        IIOMetadata metadata = writer.getDefaultImageMetadata(type, null);
        String format = metadata.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);

        IIOMetadataNode graphicControl = child(root, "GraphicControlExtension");
        graphicControl.setAttribute("disposalMethod", "restoreToBackgroundColor");
        graphicControl.setAttribute("userInputFlag", "FALSE");
        graphicControl.setAttribute("transparentColorFlag", "TRUE");
        graphicControl.setAttribute("delayTime", String.valueOf(Math.max(1, delayMs / 10)));
        graphicControl.setAttribute("transparentColorIndex", "0");

        if (firstFrame) {
            IIOMetadataNode appExtensions = child(root, "ApplicationExtensions");
            IIOMetadataNode netscape = new IIOMetadataNode("ApplicationExtension");
            netscape.setAttribute("applicationID", "NETSCAPE");
            netscape.setAttribute("authenticationCode", "2.0");
            netscape.setUserObject(new byte[] {1, 0, 0});
            appExtensions.appendChild(netscape);
        }

        metadata.setFromTree(format, root);
        return metadata;
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (name.equals(root.item(i).getNodeName())) {
                return (IIOMetadataNode) root.item(i);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }

    private static byte[] rgba(BufferedImage image) {
        byte[] rgba = new byte[image.getWidth() * image.getHeight() * 4];
        int offset = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                rgba[offset++] = (byte) ((argb >>> 16) & 255);
                rgba[offset++] = (byte) ((argb >>> 8) & 255);
                rgba[offset++] = (byte) (argb & 255);
                rgba[offset++] = (byte) ((argb >>> 24) & 255);
            }
        }
        return rgba;
    }

    private static final class Palette {
        private static final int TRANSPARENT_KEY = -1;
        private static final int TRANSPARENT_COLOR = 0xFF00FF;

        private final int[] colors = new int[256];
        private final Map<Integer, Integer> exact = new LinkedHashMap<>();
        private boolean exactOnly = true;

        private static Palette fromFrames(byte[][] frames) {
            Palette palette = new Palette();
            palette.colors[0] = TRANSPARENT_COLOR;
            palette.exact.put(TRANSPARENT_KEY, 0);
            int next = 1;
            for (byte[] frame : frames) {
                for (int i = 0; i < frame.length; i += 4) {
                    int alpha = frame[i + 3] & 255;
                    int color = ((frame[i] & 255) << 16) | ((frame[i + 1] & 255) << 8) | (frame[i + 2] & 255);
                    int key = alpha == 0 ? TRANSPARENT_KEY : color;
                    if (!palette.exact.containsKey(key)) {
                        if (next < 256) {
                            palette.exact.put(key, next);
                            palette.colors[next++] = color;
                        } else {
                            palette.exactOnly = false;
                            return quantizedPalette();
                        }
                    }
                }
            }
            return palette;
        }

        private static Palette quantizedPalette() {
            Palette palette = new Palette();
            palette.exactOnly = false;
            palette.colors[0] = TRANSPARENT_COLOR;
            for (int i = 1; i < 256; i++) {
                int value = i - 1;
                int r = ((value >>> 5) & 7) * 255 / 7;
                int g = ((value >>> 2) & 7) * 255 / 7;
                int b = (value & 3) * 255 / 3;
                palette.colors[i] = (r << 16) | (g << 8) | b;
            }
            return palette;
        }

        private IndexColorModel colorModel() {
            byte[] reds = new byte[256];
            byte[] greens = new byte[256];
            byte[] blues = new byte[256];
            byte[] alphas = new byte[256];
            for (int i = 0; i < 256; i++) {
                int color = colors[i];
                reds[i] = (byte) ((color >>> 16) & 255);
                greens[i] = (byte) ((color >>> 8) & 255);
                blues[i] = (byte) (color & 255);
                alphas[i] = (byte) (i == 0 ? 0 : 255);
            }
            return new IndexColorModel(8, 256, reds, greens, blues, alphas);
        }

        private byte[] index(byte[] rgba) {
            byte[] result = new byte[rgba.length / 4];
            for (int i = 0, p = 0; i < rgba.length; i += 4, p++) {
                int alpha = rgba[i + 3] & 255;
                if (alpha == 0) {
                    result[p] = 0;
                } else {
                    int color = ((rgba[i] & 255) << 16) | ((rgba[i + 1] & 255) << 8) | (rgba[i + 2] & 255);
                    Integer exactIndex = exactOnly ? exact.get(color) : null;
                    result[p] = (byte) (exactIndex != null ? exactIndex : quantizedIndex(color));
                }
            }
            return result;
        }

        private int quantizedIndex(int color) {
            int r = (color >>> 16) & 255;
            int g = (color >>> 8) & 255;
            int b = color & 255;
            int index = 1 + ((r * 7 / 255) << 5) + ((g * 7 / 255) << 2) + (b * 3 / 255);
            return Math.min(255, index);
        }
    }
}
