package com.quackster.chroma;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class SimpleApngEncoder {
    private static final byte[] PNG_SIGNATURE = new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    private SimpleApngEncoder() {
    }

    static byte[] encode(List<BufferedImage> frames, int delayMs) {
        return encode(frames, delayMs, true);
    }

    static byte[] encode(List<BufferedImage> frames, int delayMs, boolean loop) {
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("frames");
        }

        int width = frames.get(0).getWidth();
        int height = frames.get(0).getHeight();
        byte[][] rgbaFrames = new byte[frames.size()][];
        for (int i = 0; i < frames.size(); i++) {
            BufferedImage frame = frames.get(i);
            if (frame.getWidth() != width || frame.getHeight() != height) {
                throw new IllegalArgumentException("All APNG frames must have the same size");
            }
            rgbaFrames[i] = rgba(frame);
        }

        return encodeRgba(width, height, rgbaFrames, delayMs, loop);
    }

    private static byte[] encodeRgba(int width, int height, byte[][] frames, int delayMs, boolean loop) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, PNG_SIGNATURE);
        chunk(out, "IHDR", pngHeader(width, height));

        byte[] animationControl = new byte[8];
        writeUint32(animationControl, 0, frames.length);
        writeUint32(animationControl, 4, loop ? 0 : 1);
        chunk(out, "acTL", animationControl);

        int sequence = 0;
        for (int i = 0; i < frames.length; i++) {
            chunk(out, "fcTL", frameControl(sequence++, width, height, delayMs));
            byte[] compressed = zlibStore(scanlines(width, height, frames[i]));
            if (i == 0) {
                chunk(out, "IDAT", compressed);
            } else {
                byte[] frameData = new byte[compressed.length + 4];
                writeUint32(frameData, 0, sequence++);
                System.arraycopy(compressed, 0, frameData, 4, compressed.length);
                chunk(out, "fdAT", frameData);
            }
        }

        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
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

    private static byte[] scanlines(int width, int height, byte[] rgba) {
        int scanlineLength = width * 4 + 1;
        byte[] raw = new byte[scanlineLength * height];
        for (int y = 0; y < height; y++) {
            int rawOffset = y * scanlineLength;
            int dataOffset = y * width * 4;
            raw[rawOffset] = 0;
            System.arraycopy(rgba, dataOffset, raw, rawOffset + 1, width * 4);
        }
        return raw;
    }

    private static byte[] pngHeader(int width, int height) {
        byte[] header = new byte[13];
        writeUint32(header, 0, width);
        writeUint32(header, 4, height);
        header[8] = 8;
        header[9] = 6;
        return header;
    }

    private static byte[] frameControl(int sequence, int width, int height, int delayMs) {
        byte[] frameControl = new byte[26];
        writeUint32(frameControl, 0, sequence);
        writeUint32(frameControl, 4, width);
        writeUint32(frameControl, 8, height);
        writeUint32(frameControl, 12, 0);
        writeUint32(frameControl, 16, 0);
        writeUint16(frameControl, 20, Math.max(1, Math.min(65535, delayMs)));
        writeUint16(frameControl, 22, 1000);
        frameControl[24] = 0;
        frameControl[25] = 0;
        return frameControl;
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.UTF_8);
        byte[] crcInput = new byte[typeBytes.length + data.length];
        System.arraycopy(typeBytes, 0, crcInput, 0, typeBytes.length);
        System.arraycopy(data, 0, crcInput, typeBytes.length, data.length);
        byte[] length = new byte[4];
        writeUint32(length, 0, data.length);
        write(out, length);
        write(out, typeBytes);
        write(out, data);
        byte[] crc = new byte[4];
        writeUint32(crc, 0, crc32(crcInput));
        write(out, crc);
    }

    private static byte[] zlibStore(byte[] data) {
        int blockCount = (data.length + 65534) / 65535;
        if (blockCount == 0) {
            blockCount = 1;
        }
        byte[] output = new byte[2 + data.length + blockCount * 5 + 4];
        int offset = 0;
        output[offset++] = 0x78;
        output[offset++] = 0x01;
        for (int source = 0; source < data.length || source == 0; source += 65535) {
            int length = Math.min(65535, data.length - source);
            boolean finalBlock = source + length >= data.length;
            output[offset++] = (byte) (finalBlock ? 1 : 0);
            output[offset++] = (byte) (length & 255);
            output[offset++] = (byte) ((length >>> 8) & 255);
            int inverse = (~length) & 65535;
            output[offset++] = (byte) (inverse & 255);
            output[offset++] = (byte) ((inverse >>> 8) & 255);
            if (length > 0) {
                System.arraycopy(data, source, output, offset, length);
                offset += length;
            }
            if (data.length == 0) {
                break;
            }
        }
        writeUint32(output, offset, adler32(data));
        return output;
    }

    private static int crc32(byte[] bytes) {
        int crc = -1;
        for (int i = 0; i < bytes.length; i++) {
            crc = crc ^ (bytes[i] & 255);
            for (int k = 0; k < 8; k++) {
                crc = (crc & 1) != 0 ? 0xedb88320 ^ (crc >>> 1) : crc >>> 1;
            }
        }
        return crc ^ -1;
    }

    private static int adler32(byte[] bytes) {
        int a = 1;
        int b = 0;
        for (int i = 0; i < bytes.length; i++) {
            a = (a + (bytes[i] & 255)) % 65521;
            b = (b + a) % 65521;
        }
        return (b << 16) | a;
    }

    private static void writeUint32(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) ((value >>> 24) & 255);
        bytes[offset + 1] = (byte) ((value >>> 16) & 255);
        bytes[offset + 2] = (byte) ((value >>> 8) & 255);
        bytes[offset + 3] = (byte) (value & 255);
    }

    private static void writeUint16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) ((value >>> 8) & 255);
        bytes[offset + 1] = (byte) (value & 255);
    }

    private static void write(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }
}
