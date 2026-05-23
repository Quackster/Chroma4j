package com.quackster.chromawasm;

import org.teavm.jso.JSExport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public final class ChromaWasm {
    private ChromaWasm() {
    }

    public static void main(String[] args) {
        // Entry point kept for TeaVM webapp compatibility.
    }

    @JSExport
    public static String parseSwfBase64(String base64, String sprite) {
        try {
            byte[] bytes = Base64Codec.decode(base64);
            FurniPackage furniPackage = SwfExtractor.extract(bytes, sprite == null ? "" : sprite);
            return furniPackage.toJson();
        } catch (Throwable ex) {
            return "{\"ok\":false,\"error\":\"" + Json.escape(ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage()) + "\"}";
        }
    }

    private static final class SwfExtractor {
        private static FurniPackage extract(byte[] swfBytes, String requestedSprite) throws IOException, DataFormatException {
            if (swfBytes.length < 8) {
                throw new IOException("SWF file is too short");
            }

            int signature0 = swfBytes[0] & 255;
            int signature1 = swfBytes[1] & 255;
            int signature2 = swfBytes[2] & 255;
            if (signature1 != 'W' || signature2 != 'S' || (signature0 != 'F' && signature0 != 'C')) {
                throw new IOException("Unsupported SWF signature");
            }

            byte[] body = signature0 == 'C' ? inflate(swfBytes, 8, swfBytes.length - 8) : slice(swfBytes, 8, swfBytes.length - 8);
            Reader reader = new Reader(body);
            skipRect(reader);
            reader.skip(4);

            Map<Integer, String> symbols = new LinkedHashMap<>();
            Map<Integer, ImageAsset> images = new LinkedHashMap<>();
            Map<Integer, byte[]> binaries = new LinkedHashMap<>();

            while (reader.remaining() > 0) {
                int header = reader.u16();
                int code = header >> 6;
                int length = header & 63;
                if (length == 63) {
                    length = reader.i32();
                }
                if (length < 0 || length > reader.remaining()) {
                    throw new IOException("Malformed SWF tag length");
                }

                byte[] payload = reader.bytes(length);
                Reader tag = new Reader(payload);

                if (code == 0) {
                    break;
                } else if (code == 76) {
                    readSymbolClass(tag, symbols);
                } else if (code == 87) {
                    int id = tag.u16();
                    tag.skip(4);
                    binaries.put(id, tag.bytes(tag.remaining()));
                } else if (code == 36) {
                    readLossless(code, tag, images);
                }
            }

            String sprite = detectSprite(requestedSprite, symbols);
            FurniPackage result = new FurniPackage(sprite);

            for (Map.Entry<Integer, byte[]> entry : binaries.entrySet()) {
                String symbol = symbols.get(entry.getKey());
                if (symbol == null) {
                    continue;
                }
                String type = suffix(symbol);
                String xml = cleanXml(new String(entry.getValue(), StandardCharsets.UTF_8));
                if (type.length() > 0 && xml.trim().startsWith("<")) {
                    result.xml.add(new XmlAsset(type, xml));
                }
            }

            for (Map.Entry<Integer, ImageAsset> entry : images.entrySet()) {
                String symbol = symbols.get(entry.getKey());
                if (symbol == null) {
                    continue;
                }
                ImageAsset image = entry.getValue();
                image.name = imageName(sprite, symbol);
                if (image.name.length() > 0) {
                    result.images.add(image);
                }
            }

            if (result.xml.isEmpty()) {
                throw new IOException("No furni XML binary tags found");
            }
            return result;
        }

        private static void readSymbolClass(Reader tag, Map<Integer, String> symbols) throws IOException {
            int count = tag.u16();
            for (int i = 0; i < count; i++) {
                int id = tag.u16();
                symbols.put(id, tag.cString());
            }
        }

        private static void readLossless(int code, Reader tag, Map<Integer, ImageAsset> images) throws IOException, DataFormatException {
            int id = tag.u16();
            int format = tag.u8();
            int width = tag.u16();
            int height = tag.u16();
            byte[] compressed = tag.bytes(tag.remaining());
            byte[] inflated = inflate(compressed, 0, compressed.length);

            if (format != 5) {
                throw new IOException("Unsupported DefineBitsLossless format " + format + " in tag " + code);
            }

            byte[] rgba = new byte[width * height * 4];
            int pixels = Math.min(width * height, inflated.length / 4);
            for (int i = 0; i < pixels; i++) {
                int source = i * 4;
                int target = i * 4;
                rgba[target] = inflated[source + 1];
                rgba[target + 1] = inflated[source + 2];
                rgba[target + 2] = inflated[source + 3];
                rgba[target + 3] = inflated[source];
            }
            images.put(id, ImageAsset.raw("", width, height, rgba));
        }

        private static String detectSprite(String requested, Map<Integer, String> symbols) {
            if (requested != null && requested.trim().length() > 0) {
                return stripExtension(requested.trim());
            }
            for (String symbol : symbols.values()) {
                String suffix = suffix(symbol);
                if ("assets".equals(suffix) || "visualization".equals(suffix) || "logic".equals(suffix) || "index".equals(suffix)) {
                    int end = symbol.length() - suffix.length() - 1;
                    if (end > 0) {
                        return symbol.substring(0, end);
                    }
                }
            }
            return "furni";
        }

        private static String imageName(String sprite, String symbol) {
            String prefix = sprite + "_";
            if (symbol.startsWith(prefix) && symbol.length() > prefix.length()) {
                return symbol.substring(prefix.length());
            }
            return symbol;
        }

        private static String suffix(String value) {
            int index = value.lastIndexOf('_');
            return index >= 0 && index + 1 < value.length() ? value.substring(index + 1) : "";
        }

        private static String stripExtension(String value) {
            int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
            String name = slash >= 0 ? value.substring(slash + 1) : value;
            int query = name.indexOf('?');
            if (query >= 0) {
                name = name.substring(0, query);
            }
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        }

        private static String cleanXml(String xml) {
            String cleaned = xml.replace("\uFEFF", "");
            int declaration = cleaned.indexOf("<?xml");
            if (declaration > 0) {
                cleaned = cleaned.substring(declaration);
            }
            cleaned = cleaned.replace("<graphics>", "").replace("</graphics>", "");
            return cleaned;
        }

        private static void skipRect(Reader reader) throws IOException {
            int first = reader.u8();
            int nbits = first >> 3;
            int totalBits = 5 + nbits * 4;
            int totalBytes = (totalBits + 7) / 8;
            reader.skip(totalBytes - 1);
        }

        private static byte[] inflate(byte[] data, int offset, int length) throws DataFormatException {
            Inflater inflater = new Inflater();
            inflater.setInput(data, offset, length);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished() && !inflater.needsInput()) {
                int count = inflater.inflate(buffer);
                if (count == 0) {
                    break;
                }
                out.write(buffer, 0, count);
            }
            inflater.end();
            return out.toByteArray();
        }

        private static byte[] slice(byte[] source, int offset, int length) {
            byte[] result = new byte[length];
            System.arraycopy(source, offset, result, 0, length);
            return result;
        }
    }

    private static final class FurniPackage {
        private final String sprite;
        private final List<XmlAsset> xml = new ArrayList<>();
        private final List<ImageAsset> images = new ArrayList<>();

        private FurniPackage(String sprite) {
            this.sprite = sprite;
        }

        private String toJson() {
            StringBuilder out = new StringBuilder();
            out.append("{\"ok\":true,\"sprite\":\"").append(Json.escape(sprite)).append("\",\"xml\":{");
            for (int i = 0; i < xml.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                XmlAsset item = xml.get(i);
                out.append('"').append(Json.escape(item.type)).append("\":\"").append(Json.escape(item.text)).append('"');
            }
            out.append("},\"images\":[");
            for (int i = 0; i < images.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(images.get(i).toJson());
            }
            out.append("]}");
            return out.toString();
        }
    }

    private static final class XmlAsset {
        private final String type;
        private final String text;

        private XmlAsset(String type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    private static final class ImageAsset {
        private String name;
        private String kind;
        private String mime;
        private int width;
        private int height;
        private byte[] data;

        private static ImageAsset encoded(String name, String mime, byte[] data) {
            ImageAsset image = new ImageAsset();
            image.name = name;
            image.kind = "encoded";
            image.mime = mime;
            image.data = data;
            return image;
        }

        private static ImageAsset raw(String name, int width, int height, byte[] rgba) {
            ImageAsset image = new ImageAsset();
            image.name = name;
            image.kind = "rgba";
            image.mime = "image/rgba";
            image.width = width;
            image.height = height;
            image.data = rgba;
            return image;
        }

        private String toJson() {
            return "{\"name\":\"" + Json.escape(name)
                    + "\",\"kind\":\"" + kind
                    + "\",\"mime\":\"" + mime
                    + "\",\"width\":" + width
                    + ",\"height\":" + height
                    + ",\"data\":\"" + Base64Codec.encode(data) + "\"}";
        }
    }

    private static final class Reader {
        private final byte[] remainingBytes;
        private int position;

        private Reader(byte[] bytes) {
            this.remainingBytes = bytes;
        }

        private int remaining() {
            return remainingBytes.length - position;
        }

        private int u8() throws IOException {
            ensure(1);
            return remainingBytes[position++] & 255;
        }

        private int u16() throws IOException {
            ensure(2);
            int value = (remainingBytes[position] & 255) | ((remainingBytes[position + 1] & 255) << 8);
            position += 2;
            return value;
        }

        private int i32() throws IOException {
            ensure(4);
            int value = (remainingBytes[position] & 255)
                    | ((remainingBytes[position + 1] & 255) << 8)
                    | ((remainingBytes[position + 2] & 255) << 16)
                    | ((remainingBytes[position + 3] & 255) << 24);
            position += 4;
            return value;
        }

        private byte[] bytes(int length) throws IOException {
            ensure(length);
            byte[] data = new byte[length];
            System.arraycopy(remainingBytes, position, data, 0, length);
            position += length;
            return data;
        }

        private byte[] peekRemaining() {
            byte[] data = new byte[remaining()];
            System.arraycopy(remainingBytes, position, data, 0, data.length);
            return data;
        }

        private String cString() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (remaining() > 0) {
                int b = u8();
                if (b == 0) {
                    break;
                }
                out.write(b);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }

        private void skip(int count) throws IOException {
            ensure(count);
            position += count;
        }

        private void ensure(int count) throws IOException {
            if (count < 0 || position + count > remainingBytes.length) {
                throw new IOException("Unexpected end of SWF data");
            }
        }
    }

    private static final class Json {
        private static String escape(String value) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '"' || c == '\\') {
                    out.append('\\').append(c);
                } else if (c == '\n') {
                    out.append("\\n");
                } else if (c == '\r') {
                    out.append("\\r");
                } else if (c == '\t') {
                    out.append("\\t");
                } else if (c < 32) {
                    out.append("\\u00");
                    String hex = Integer.toHexString(c);
                    if (hex.length() == 1) {
                        out.append('0');
                    }
                    out.append(hex);
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
    }

    private static final class Base64Codec {
        private static final char[] ENCODE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

        private static byte[] decode(String input) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int buffer = 0;
            int bits = 0;
            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);
                if (c == '=') {
                    break;
                }
                int value = decodeValue(c);
                if (value < 0) {
                    continue;
                }
                buffer = (buffer << 6) | value;
                bits += 6;
                if (bits >= 8) {
                    bits -= 8;
                    out.write((buffer >> bits) & 255);
                }
            }
            return out.toByteArray();
        }

        private static String encode(byte[] data) {
            StringBuilder out = new StringBuilder(((data.length + 2) / 3) * 4);
            for (int i = 0; i < data.length; i += 3) {
                int b0 = data[i] & 255;
                int b1 = i + 1 < data.length ? data[i + 1] & 255 : 0;
                int b2 = i + 2 < data.length ? data[i + 2] & 255 : 0;
                out.append(ENCODE[b0 >> 2]);
                out.append(ENCODE[((b0 & 3) << 4) | (b1 >> 4)]);
                out.append(i + 1 < data.length ? ENCODE[((b1 & 15) << 2) | (b2 >> 6)] : '=');
                out.append(i + 2 < data.length ? ENCODE[b2 & 63] : '=');
            }
            return out.toString();
        }

        private static int decodeValue(char c) throws IOException {
            if (c >= 'A' && c <= 'Z') {
                return c - 'A';
            }
            if (c >= 'a' && c <= 'z') {
                return c - 'a' + 26;
            }
            if (c >= '0' && c <= '9') {
                return c - '0' + 52;
            }
            if (c == '+') {
                return 62;
            }
            if (c == '/') {
                return 63;
            }
            if (c == '\n' || c == '\r' || c == '\t' || c == ' ') {
                return -1;
            }
            throw new IOException("Invalid base64 input");
        }
    }
}
