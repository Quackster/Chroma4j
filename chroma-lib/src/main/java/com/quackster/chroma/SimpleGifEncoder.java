package com.quackster.chroma;

import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

final class SimpleGifEncoder {
    private SimpleGifEncoder() {
    }

    static byte[] encode(List<BufferedImage> frames, int delayMs) {
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("frames");
        }

        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageWriter writer = gifWriter();
            try (ImageOutputStream stream = ImageIO.createImageOutputStream(output)) {
                writer.setOutput(stream);
                writer.prepareWriteSequence(null);
                for (int i = 0; i < frames.size(); i++) {
                    BufferedImage frame = frames.get(i);
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

    private static IIOMetadata metadata(ImageWriter writer, BufferedImage image, int delayMs, boolean firstFrame) throws IOException {
        ImageTypeSpecifier type = ImageTypeSpecifier.createFromRenderedImage(image);
        IIOMetadata metadata = writer.getDefaultImageMetadata(type, null);
        String format = metadata.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);

        IIOMetadataNode graphicControl = child(root, "GraphicControlExtension");
        graphicControl.setAttribute("disposalMethod", "none");
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
}
