package com.quackster.chromawasm;

import org.teavm.jso.JSExport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
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

    @JSExport
    public static String renderSwfBase64(String base64, String optionsJson) {
        try {
            RenderOptions options = RenderOptions.parse(optionsJson == null ? "{}" : optionsJson);
            byte[] bytes = Base64Codec.decode(base64);
            FurniPackage furniPackage = SwfExtractor.extract(bytes, options.sprite);
            RenderResult result = WasmRenderer.render(furniPackage, options);
            return "{\"ok\":true,\"width\":" + result.width
                    + ",\"height\":" + result.height
                    + ",\"cropX\":" + result.cropX
                    + ",\"cropY\":" + result.cropY
                    + ",\"backgroundWidth\":" + result.backgroundWidth
                    + ",\"backgroundHeight\":" + result.backgroundHeight
                    + ",\"backgroundDeferred\":" + result.backgroundDeferred
                    + ",\"format\":\"" + result.format + "\""
                    + ",\"mime\":\"" + result.mime
                    + "\",\"isAnimated\":" + result.animated
                    + ",\"dataBase64\":\"" + Base64Codec.encode(result.data) + "\""
                    + (result.addData == null ? "" : ",\"addBase64\":\"" + Base64Codec.encode(result.addData) + "\"")
                    + (result.png ? ",\"pngBase64\":\"" + Base64Codec.encode(result.data) + "\"" : "")
                    + "}";
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
                    throw new IOException("No SymbolClass entry for binary data " + entry.getKey());
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
            String cleaned = xml;
            if (cleaned.indexOf("\n<?xml") >= 0) {
                cleaned = cleaned.replace("\n<?xml", "<?xml");
            }
            if (cleaned.indexOf("<graphics>") >= 0) {
                cleaned = cleaned.replace("<graphics>", "");
                cleaned = cleaned.replace("</graphics>", "            ");
            }
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

    private static final class RenderOptions {
        private String sprite = "";
        private boolean small;
        private int state;
        private int direction;
        private int color;
        private String canvas = "transparent";
        private boolean crop = true;
        private boolean shadow;
        private boolean icon;
        private boolean background;
        private boolean gif;
        private boolean apng;
        private String format = "";
        private boolean separateAdd;
        private boolean loop = true;
        private int backgroundWidth;
        private int backgroundHeight;
        private byte[] backgroundRgba;
        private boolean deferBackground;

        private static RenderOptions parse(String json) {
            RenderOptions options = new RenderOptions();
            options.sprite = Json.readString(json, "sprite", "");
            options.small = Json.readBoolean(json, "small", false) || Json.readBoolean(json, "s", false);
            options.state = Json.readInt(json, "state", 0);
            if (options.state >= 101) {
                options.state = 0;
            }
            options.direction = Json.readInt(json, "direction", 0);
            if (Json.hasKey(json, "rotation")) {
                options.direction = Json.readInt(json, "rotation", options.direction);
            }
            options.color = Json.hasKey(json, "colour")
                    ? Json.readInt(json, "colour", 0)
                    : Json.readInt(json, "color", 0);
            options.canvas = Json.readString(json, "canvas", "transparent");
            options.crop = Json.hasKey(json, "crop") ? Json.readBoolean(json, "crop", true) : true;
            options.shadow = Json.readBoolean(json, "shadow", false);
            options.icon = Json.readBoolean(json, "icon", false);
            options.background = Json.readBoolean(json, "background", false) || Json.readBoolean(json, "bg", false);
            options.format = Json.readString(json, "format", "").toLowerCase();
            options.apng = Json.readBoolean(json, "apng", false) || "apng".equals(options.format);
            options.gif = !options.apng && (Json.readBoolean(json, "gif", false) || "gif".equals(options.format));
            options.separateAdd = Json.readBoolean(json, "separateAdd", false);
            options.loop = Json.hasKey(json, "loop") ? Json.readBoolean(json, "loop", true) : true;
            options.backgroundWidth = Json.readInt(json, "backgroundWidth", 0);
            options.backgroundHeight = Json.readInt(json, "backgroundHeight", 0);
            String backgroundRgba = Json.readString(json, "backgroundRgbaBase64", "");
            if (backgroundRgba.length() > 0) {
                try {
                    options.backgroundRgba = Base64Codec.decode(backgroundRgba);
                } catch (IOException ignored) {
                    options.backgroundRgba = null;
                }
            }
            return options;
        }
    }

    private static final class RenderResult {
        private final int width;
        private final int height;
        private final int cropX;
        private final int cropY;
        private final int backgroundWidth;
        private final int backgroundHeight;
        private final boolean backgroundDeferred;
        private final byte[] data;
        private final byte[] addData;
        private final String mime;
        private final String format;
        private final boolean animated;
        private final boolean png;

        private RenderResult(
                int width,
                int height,
                int cropX,
                int cropY,
                int backgroundWidth,
                int backgroundHeight,
                boolean backgroundDeferred,
                byte[] data,
                byte[] addData,
                String mime,
                String format,
                boolean animated,
                boolean png) {
            this.width = width;
            this.height = height;
            this.cropX = cropX;
            this.cropY = cropY;
            this.backgroundWidth = backgroundWidth;
            this.backgroundHeight = backgroundHeight;
            this.backgroundDeferred = backgroundDeferred;
            this.data = data;
            this.addData = addData;
            this.mime = mime;
            this.format = format;
            this.animated = animated;
            this.png = png;
        }
    }

    private static final class WasmRenderer {
        private static final int CANVAS_WIDTH = 1200;
        private static final int CANVAS_HEIGHT = 1200;
        private static final int MIN_Z = -2147483648;

        private static RenderResult render(FurniPackage furni, RenderOptions options) throws IOException {
            XmlNode assetsXml = XmlNode.parse(requiredXml(furni, "assets"));
            XmlNode visualizationXml = XmlNode.parse(requiredXml(furni, "visualization"));
            Map<String, ImageEntry> images = buildImageAliases(furni.sprite, assetsXml, furni.images);

            String size = options.small ? "32" : "64";
            int maxState = readMaxState(visualizationXml, size, options.direction);
            if (options.state > maxState) {
                options.state = 0;
            }

            int renderWidth = options.background && options.backgroundRgba != null && options.backgroundWidth > 0 ? options.backgroundWidth : CANVAS_WIDTH;
            int renderHeight = options.background && options.backgroundRgba != null && options.backgroundHeight > 0 ? options.backgroundHeight : CANVAS_HEIGHT;
            options.deferBackground = options.gif && options.background && options.backgroundRgba != null && options.backgroundWidth > 0 && options.backgroundHeight > 0;
            boolean animatedOutput = options.gif || options.apng;
            int frameCount = animatedOutput ? animationFrameCount(visualizationXml, size, options.state) : 1;
            RenderedFrame[] frames = new RenderedFrame[frameCount];
            Bounds crop = null;
            for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
                RenderedFrame frame = renderFrame(furni.sprite, assetsXml, visualizationXml, images, options, renderWidth, renderHeight, frameIndex);
                frames[frameIndex] = frame;
                if (options.crop) {
                    Bounds frameCrop = frameCropBounds(frame, renderWidth, renderHeight, trimColors(options));
                    if (frameCrop != null) {
                        crop = crop == null ? frameCrop : crop.union(frameCrop);
                    }
                }
            }
            if (crop == null) {
                crop = new Bounds(0, 0, renderWidth, renderHeight);
            }
            if (animatedOutput) {
                byte[][] rgbaFrames = new byte[frameCount][];
                byte[][] addRgbaFrames = hasAddOverlay(frames) ? new byte[frameCount][] : null;
                for (int i = 0; i < frameCount; i++) {
                    rgbaFrames[i] = cropRgba(frames[i].canvas, renderWidth, crop);
                    if (addRgbaFrames != null) {
                        addRgbaFrames[i] = cropRgba(frames[i].addCanvas, renderWidth, crop);
                    }
                }
                if (options.apng) {
                    return new RenderResult(
                            crop.width,
                            crop.height,
                            crop.x,
                            crop.y,
                            renderWidth,
                            renderHeight,
                            false,
                            ApngEncoder.encode(crop.width, crop.height, rgbaFrames, 120, options.loop),
                            addRgbaFrames == null ? null : ApngEncoder.encode(crop.width, crop.height, addRgbaFrames, 120, options.loop),
                            "image/png",
                            "apng",
                            frameCount > 1,
                            false);
                }
                return new RenderResult(
                        crop.width,
                        crop.height,
                        crop.x,
                        crop.y,
                        renderWidth,
                        renderHeight,
                        options.deferBackground,
                        GifEncoder.encode(crop.width, crop.height, rgbaFrames, 120, options.loop),
                        addRgbaFrames == null ? null : GifEncoder.encode(crop.width, crop.height, addRgbaFrames, 120, options.loop),
                        "image/gif",
                        "gif",
                        frameCount > 1,
                        false);
            }
            byte[] rgba = cropRgba(frames[0].canvas, renderWidth, crop);
            byte[] addRgba = hasAddOverlay(frames) ? cropRgba(frames[0].addCanvas, renderWidth, crop) : null;
            return new RenderResult(
                    crop.width,
                    crop.height,
                    crop.x,
                    crop.y,
                    renderWidth,
                    renderHeight,
                    false,
                    PngEncoder.encode(crop.width, crop.height, rgba),
                    addRgba == null ? null : PngEncoder.encode(crop.width, crop.height, addRgba),
                    "image/png",
                    "png",
                    false,
                    true);
        }

        private static RenderedFrame renderFrame(
                String sprite,
                XmlNode assetsXml,
                XmlNode visualizationXml,
                Map<String, ImageEntry> images,
                RenderOptions options,
                int renderWidth,
                int renderHeight,
                int animationFrameIndex) throws IOException {

            List<RenderAsset> renderAssets = collectWithDirectionFallback(sprite, assetsXml, visualizationXml, images, options, renderWidth, renderHeight, animationFrameIndex);
            int[] canvas = new int[renderWidth * renderHeight];
            int[] addCanvas = options.separateAdd ? new int[renderWidth * renderHeight] : null;
            fillCanvas(canvas, renderWidth, renderHeight, options);
            for (int i = 0; i < renderAssets.size(); i++) {
                drawAsset(canvas, addCanvas, renderWidth, renderHeight, renderAssets.get(i), options);
            }
            return new RenderedFrame(canvas, addCanvas);
        }

        private static String requiredXml(FurniPackage furni, String type) throws IOException {
            for (int i = 0; i < furni.xml.size(); i++) {
                XmlAsset asset = furni.xml.get(i);
                if (type.equals(asset.type)) {
                    return asset.text;
                }
            }
            throw new IOException("Missing " + type + " XML");
        }

        private static Map<String, ImageEntry> buildImageAliases(String sprite, XmlNode assetsXml, List<ImageAsset> packageImages) {
            Map<String, ImageEntry> aliases = new LinkedHashMap<>();
            for (int i = 0; i < packageImages.size(); i++) {
                ImageAsset image = packageImages.get(i);
                ImageEntry entry = new ImageEntry(image, false);
                aliases.put(image.name, entry);
                aliases.put(normalizeName(sprite, image.name), entry);
            }
            List<XmlNode> assets = assetsXml.descendants("asset");
            for (int i = 0; i < assets.size(); i++) {
                XmlNode asset = assets.get(i);
                String name = normalizeName(sprite, asset.attr("name"));
                String source = normalizeName(sprite, asset.attr("source"));
                if (source.length() > 0 && aliases.containsKey(source) && !aliases.containsKey(name)) {
                    ImageEntry sourceEntry = aliases.get(source);
                    aliases.put(name, new ImageEntry(sourceEntry.image, sourceEntry.flipH || "1".equals(asset.attr("flipH"))));
                }
            }
            return aliases;
        }

        private static List<RenderAsset> collectWithDirectionFallback(
                String sprite,
                XmlNode assetsXml,
                XmlNode visualizationXml,
                Map<String, ImageEntry> images,
                RenderOptions options,
                int renderWidth,
                int renderHeight,
                int animationFrameIndex) throws IOException {

            List<RenderAsset> preferred = collectRenderAssets(sprite, assetsXml, visualizationXml, images, options, options.direction, renderWidth, renderHeight, animationFrameIndex);
            if (!preferred.isEmpty() || options.icon) {
                return preferred;
            }
            int[] directions = {0, 2, 4, 6};
            for (int i = 0; i < directions.length; i++) {
                if (directions[i] == options.direction) {
                    continue;
                }
                List<RenderAsset> fallback = collectRenderAssets(sprite, assetsXml, visualizationXml, images, options, directions[i], renderWidth, renderHeight, animationFrameIndex);
                if (!fallback.isEmpty()) {
                    return fallback;
                }
            }
            return preferred;
        }

        private static List<RenderAsset> collectRenderAssets(
                String sprite,
                XmlNode assetsXml,
                XmlNode visualizationXml,
                Map<String, ImageEntry> images,
                RenderOptions options,
                int direction,
                int renderWidth,
                int renderHeight,
                int animationFrameIndex) throws IOException {

            String size = options.small ? "32" : "64";
            Map<Integer, LayerInfo> layers = readLayers(visualizationXml, size, direction);
            Map<Integer, String> colorLayers = readColorLayers(visualizationXml, size, options.color);
            Map<Integer, AnimationFrameRef> animations = readAnimationFrames(visualizationXml, size, options.state, animationFrameIndex, direction);
            List<RenderAsset> candidates = new ArrayList<>();
            List<XmlNode> assetNodes = assetsXml.descendants("asset");
            for (int i = 0; i < assetNodes.size(); i++) {
                XmlNode node = assetNodes.get(i);
                String name = node.attr("name");
                if (name.indexOf(".props") >= 0 || name.startsWith("s_" + sprite)) {
                    continue;
                }
                if (options.icon ? name.indexOf("_icon_") < 0 : name.indexOf("_icon_") >= 0) {
                    continue;
                }
                ParsedAsset parsed = parseAssetName(sprite, name, options.icon);
                if (parsed == null || (!options.icon && !size.equals(parsed.size))) {
                    continue;
                }
                ImageEntry image = images.get(normalizeName(sprite, name));
                if (image == null && node.attr("source").length() > 0) {
                    image = images.get(normalizeName(sprite, node.attr("source")));
                }
                if (image == null) {
                    continue;
                }
                String color = colorLayers.get(parsed.layer);
                if (colorLayers.containsKey(parsed.layer) && color == null) {
                    continue;
                }
                LayerInfo layerInfo = layers.get(parsed.layer);
                if (layerInfo == null) {
                    layerInfo = new LayerInfo();
                    layerInfo.z = parsed.layer;
                }
                RenderAsset asset = new RenderAsset();
                asset.name = name;
                asset.image = image.image;
                asset.flipH = "1".equals(node.attr("flipH")) || image.flipH;
                int x = parseInt(node.attr("x"), 0);
                int y = parseInt(node.attr("y"), 0);
                asset.x = ((asset.flipH ? image.image.width - x : x) + renderWidth / 2);
                asset.y = y + renderHeight / 2;
                asset.z = (layerInfo.zSet ? layerInfo.z : parsed.layer) + parsed.layer;
                asset.layer = parsed.layer;
                asset.direction = parsed.direction;
                asset.frame = parsed.frame;
                asset.ink = layerInfo.ink;
                asset.alpha = layerInfo.alpha;
                asset.color = color == null ? "" : color;
                asset.shadow = name.indexOf("_sd_") >= 0;
                candidates.add(asset);
            }

            int highestLayer = 0;
            for (int i = 0; i < candidates.size(); i++) {
                RenderAsset asset = candidates.get(i);
                if (!asset.shadow && asset.layer + 1 > highestLayer) {
                    highestLayer = asset.layer + 1;
                }
            }
            if (highestLayer == 0) {
                throw new IOException("Sequence contains no elements");
            }

            List<RenderAsset> assets = new ArrayList<>();
            Map<String, RenderAsset> candidateByName = new LinkedHashMap<>();
            for (int i = 0; i < candidates.size(); i++) {
                RenderAsset candidate = candidates.get(i);
                if (!candidateByName.containsKey(candidate.name)) {
                    candidateByName.put(candidate.name, candidate);
                }
            }
            for (int layer = 0; layer < highestLayer; layer++) {
                AnimationFrameRef animationFrame = animations.get(layer);
                int frame = animationFrame == null ? 0 : animationFrame.id;
                AnimationFrameRef baseFrame = resolveAnimationFrame(visualizationXml, size, options.state, layer, 0, direction);
                RenderAsset asset = candidateByName.get(frameAssetName(sprite, size, options.icon, layer, direction, frame));
                if (asset == null && baseFrame != null) {
                    asset = candidateByName.get(frameAssetName(sprite, size, options.icon, layer, direction, baseFrame.id));
                    if (asset != null) {
                        animationFrame = baseFrame;
                    }
                }
                if (asset == null) {
                    asset = candidateByName.get(frameAssetName(sprite, size, options.icon, layer, direction, 0));
                    if (asset != null) {
                        animationFrame = null;
                    }
                }
                if (asset != null) {
                    if (animationFrame != null) {
                        asset.x -= animationFrame.x;
                        asset.y -= animationFrame.y;
                    }
                    assets.add(asset);
                }
            }
            List<RenderAsset> filtered = new ArrayList<>();
            for (int i = 0; i < assets.size(); i++) {
                RenderAsset asset = assets.get(i);
                if (!options.shadow && asset.shadow) {
                    continue;
                }
                if (asset.shadow) {
                    asset.z = MIN_Z;
                }
                filtered.add(asset);
            }
            sortAssets(filtered);
            return filtered;
        }

        private static Map<Integer, LayerInfo> readLayers(XmlNode doc, String size, int direction) {
            Map<Integer, LayerInfo> result = new LinkedHashMap<>();
            XmlNode visualization = visualization(doc, size);
            if (visualization == null) {
                return result;
            }
            List<XmlNode> baseLayers = children(firstChild(visualization, "layers"), "layer");
            XmlNode directionsNode = firstChild(visualization, "directions");
            XmlNode directionNode = null;
            List<XmlNode> directions = children(directionsNode, "direction");
            for (int i = 0; i < directions.size(); i++) {
                if (String.valueOf(direction).equals(directions.get(i).attr("id"))) {
                    directionNode = directions.get(i);
                    break;
                }
            }
            List<XmlNode> layerNodes = baseLayers.isEmpty() ? children(directionNode, "layer") : baseLayers;
            for (int i = 0; i < layerNodes.size(); i++) {
                XmlNode layer = layerNodes.get(i);
                int id = parseInt(layer.attr("id"), -1);
                if (id >= 0) {
                    LayerInfo info = new LayerInfo();
                    info.ink = layer.attr("ink");
                    if (layer.hasAttr("z")) {
                        info.z = parseInt(layer.attr("z"), id);
                        info.zSet = true;
                    }
                    if (layer.hasAttr("alpha")) {
                        info.alpha = parseInt(layer.attr("alpha"), -1);
                    }
                    result.put(id, info);
                }
            }
            return result;
        }

        private static Map<Integer, String> readColorLayers(XmlNode doc, String size, int colorId) {
            Map<Integer, String> result = new LinkedHashMap<>();
            XmlNode visualization = visualization(doc, size);
            if (visualization == null) {
                return result;
            }
            XmlNode colorsNode = firstChild(visualization, "colors");
            List<XmlNode> colors = children(colorsNode, "color");
            XmlNode color = null;
            for (int i = 0; i < colors.size(); i++) {
                if (String.valueOf(colorId).equals(colors.get(i).attr("id"))) {
                    color = colors.get(i);
                    break;
                }
            }
            List<XmlNode> layers = children(color, "colorLayer");
            for (int i = 0; i < layers.size(); i++) {
                XmlNode layer = layers.get(i);
                result.put(parseInt(layer.attr("id"), -1), layer.hasAttr("color") ? layer.attr("color") : null);
            }
            return result;
        }

        private static Map<Integer, AnimationFrameRef> readAnimationFrames(XmlNode doc, String size, int state, int animationFrameIndex, int direction) {
            Map<Integer, AnimationFrameRef> result = new LinkedHashMap<>();
            XmlNode visualization = visualization(doc, size);
            if (visualization == null) {
                return result;
            }
            XmlNode animationsNode = firstChild(visualization, "animations");
            List<XmlNode> animations = children(animationsNode, "animation");
            for (int i = 0; i < animations.size(); i++) {
                XmlNode animation = animations.get(i);
                if (!String.valueOf(state).equals(animation.attr("id"))) {
                    continue;
                }
                List<XmlNode> layers = children(animation, "animationLayer");
                for (int j = 0; j < layers.size(); j++) {
                    XmlNode layer = layers.get(j);
                    int id = parseInt(layer.attr("id"), -1);
                    AnimationFrameRef frame = resolveAnimationFrame(layer, animationFrameIndex, direction);
                    if (id >= 0 && frame != null) {
                        result.put(id, frame);
                    }
                }
            }
            return result;
        }

        private static AnimationFrameRef resolveAnimationFrame(XmlNode layer, int animationFrameIndex, int direction) {
            List<AnimationSequence> sequences = readAnimationSequences(layer);
            int availableFrameCount = 0;
            for (int i = 0; i < sequences.size(); i++) {
                availableFrameCount += sequences.get(i).availableFrameCount();
            }
            if (availableFrameCount < 1) {
                return null;
            }
            int localFrame = Math.min(Math.max(0, animationFrameIndex), availableFrameCount - 1);
            int cursor = 0;
            for (int i = 0; i < sequences.size(); i++) {
                AnimationSequence sequence = sequences.get(i);
                int count = sequence.availableFrameCount();
                if (localFrame < cursor + count) {
                    return sequence.availableFrame(localFrame - cursor, direction);
                }
                cursor += count;
            }
            return null;
        }

        private static AnimationFrameRef resolveAnimationFrame(XmlNode doc, String size, int state, int layerId, int animationFrameIndex, int direction) {
            XmlNode visualization = visualization(doc, size);
            if (visualization == null) {
                return null;
            }
            List<XmlNode> animations = children(firstChild(visualization, "animations"), "animation");
            for (int i = 0; i < animations.size(); i++) {
                XmlNode animation = animations.get(i);
                if (!String.valueOf(state).equals(animation.attr("id"))) {
                    continue;
                }
                List<XmlNode> layers = children(animation, "animationLayer");
                for (int j = 0; j < layers.size(); j++) {
                    XmlNode layer = layers.get(j);
                    if (parseInt(layer.attr("id"), -1) == layerId) {
                        return resolveAnimationFrame(layer, animationFrameIndex, direction);
                    }
                }
            }
            return null;
        }

        private static List<AnimationSequence> readAnimationSequences(XmlNode layer) {
            List<AnimationSequence> sequences = new ArrayList<>();
            List<XmlNode> frameSequences = children(layer, "frameSequence");
            for (int i = 0; i < frameSequences.size(); i++) {
                XmlNode frameSequence = frameSequences.get(i);
                AnimationSequence sequence = new AnimationSequence(Math.max(1, parseInt(frameSequence.attr("loopCount"), 1)));
                List<XmlNode> frames = children(frameSequence, "frame");
                for (int j = 0; j < frames.size(); j++) {
                    XmlNode frame = frames.get(j);
                    AnimationFrameRef frameRef = new AnimationFrameRef(
                            parseInt(frame.attr("id"), 0),
                            parseInt(frame.attr("x"), 0),
                            parseInt(frame.attr("y"), 0));
                    List<XmlNode> offsets = children(firstChild(frame, "offsets"), "offset");
                    for (int k = 0; k < offsets.size(); k++) {
                        XmlNode offset = offsets.get(k);
                        frameRef.offsets.put(parseInt(offset.attr("direction"), 0), new int[] {
                                parseInt(offset.attr("x"), frameRef.x),
                                parseInt(offset.attr("y"), frameRef.y)
                        });
                    }
                    sequence.frames.add(frameRef);
                }
                sequences.add(sequence);
            }
            return sequences;
        }

        private static int animationFrameCount(XmlNode doc, String size, int state) {
            int count = 1;
            XmlNode visualization = visualization(doc, size);
            if (visualization == null) {
                return count;
            }
            List<XmlNode> animations = children(firstChild(visualization, "animations"), "animation");
            for (int i = 0; i < animations.size(); i++) {
                XmlNode animation = animations.get(i);
                if (!String.valueOf(state).equals(animation.attr("id"))) {
                    continue;
                }
                List<XmlNode> layers = children(animation, "animationLayer");
                for (int j = 0; j < layers.size(); j++) {
                    count = Math.max(count, animationLayerFrameCount(layers.get(j)));
                }
            }
            return count;
        }

        private static int animationLayerFrameCount(XmlNode layer) {
            int sequenceFrames = 0;
            List<XmlNode> sequences = children(layer, "frameSequence");
            for (int i = 0; i < sequences.size(); i++) {
                sequenceFrames += children(sequences.get(i), "frame").size();
            }
            return Math.max(1, sequenceFrames);
        }

        private static String frameAssetName(String sprite, String size, boolean icon, int layer, int direction, int frame) {
            String layerName = String.valueOf((char) ('a' + layer));
            if (icon) {
                return sprite + "_icon_" + layerName;
            }
            return sprite + "_" + size + "_" + layerName + "_" + direction + "_" + frame;
        }

        private static int readMaxState(XmlNode doc, String size, int direction) {
            XmlNode visualization = visualization(doc, size);
            if (visualization == null) {
                return 0;
            }
            List<XmlNode> animations = children(firstChild(visualization, "animations"), "animation");
            if (animations.isEmpty()) {
                XmlNode directionsNode = firstChild(visualization, "directions");
                List<XmlNode> directions = children(directionsNode, "direction");
                for (int i = 0; i < directions.size(); i++) {
                    if (String.valueOf(direction).equals(directions.get(i).attr("id"))) {
                        animations = children(firstChild(directions.get(i), "animations"), "animation");
                        break;
                    }
                }
            }
            int max = 0;
            for (int i = 0; i < animations.size(); i++) {
                int id = parseInt(animations.get(i).attr("id"), 0);
                if (id > max) {
                    max = id;
                }
            }
            return max;
        }

        private static XmlNode visualization(XmlNode doc, String size) {
            List<XmlNode> visualizations = doc.descendants("visualization");
            for (int i = 0; i < visualizations.size(); i++) {
                if (size.equals(visualizations.get(i).attr("size"))) {
                    return visualizations.get(i);
                }
            }
            return null;
        }

        private static ParsedAsset parseAssetName(String sprite, String name, boolean icon) {
            String normalized = normalizeName(sprite, name);
            List<String> parts = split(normalized, '_');
            if (icon) {
                if (parts.size() < 2 || parts.get(1).length() == 0) {
                    return null;
                }
                ParsedAsset parsed = new ParsedAsset();
                parsed.size = parts.get(0);
                parsed.layer = Character.toUpperCase(parts.get(1).charAt(0)) - 'A';
                parsed.direction = 0;
                parsed.frame = 0;
                return parsed;
            }
            if (parts.size() < 4 || parts.get(1).length() == 0) {
                return null;
            }
            ParsedAsset parsed = new ParsedAsset();
            parsed.size = parts.get(0);
            parsed.layer = Character.toUpperCase(parts.get(1).charAt(0)) - 'A';
            parsed.direction = parseInt(parts.get(2), Integer.MIN_VALUE);
            parsed.frame = parseInt(parts.get(3), Integer.MIN_VALUE);
            if (parsed.direction == Integer.MIN_VALUE || parsed.frame == Integer.MIN_VALUE) {
                return null;
            }
            return parsed;
        }

        private static void drawAsset(int[] canvas, int[] addCanvas, int canvasWidth, int canvasHeight, RenderAsset asset, RenderOptions options) {
            int width = asset.image.width;
            int height = asset.image.height;
            int x = canvasWidth - asset.x;
            int y = canvasHeight - asset.y;
            byte[] source = asset.image.data;
            if (asset.flipH) {
                source = flipHorizontal(source, width, height);
            }
            if (asset.alpha >= 0) {
                source = applyAlpha(source, asset.alpha);
            }
            if (asset.color.length() > 0) {
                source = applyTint(source, asset.color, 255);
            }
            if (asset.shadow) {
                source = applyOpacity(source, 0.2);
            }
            if ("ADD".equals(asset.ink) || "33".equals(asset.ink)) {
                if (addCanvas == null) {
                    drawAdd(canvas, canvasWidth, canvasHeight, source, width, height, x, y, options);
                } else {
                    drawAddOverlay(addCanvas, canvasWidth, canvasHeight, source, width, height, x, y);
                }
            } else {
                drawNormal(canvas, canvasWidth, canvasHeight, source, width, height, x, y);
            }
        }

        private static void drawNormal(int[] canvas, int canvasWidth, int canvasHeight, byte[] source, int sourceWidth, int sourceHeight, int x, int y) {
            int startX = Math.max(0, x);
            int startY = Math.max(0, y);
            int endX = Math.min(canvasWidth, x + sourceWidth);
            int endY = Math.min(canvasHeight, y + sourceHeight);
            for (int cy = startY; cy < endY; cy++) {
                for (int cx = startX; cx < endX; cx++) {
                    int sourceIndex = ((cy - y) * sourceWidth + (cx - x)) * 4;
                    int fgAlpha = source[sourceIndex + 3] & 255;
                    if (fgAlpha == 0) {
                        continue;
                    }
                    int bgPixel = canvas[cy * canvasWidth + cx];
                    int bgAlpha = alpha(bgPixel);
                    int outAlpha = blendNormalAlpha(fgAlpha, bgAlpha);
                    int r = blendNormalChannel(source[sourceIndex] & 255, fgAlpha, red(bgPixel), bgAlpha, outAlpha);
                    int g = blendNormalChannel(source[sourceIndex + 1] & 255, fgAlpha, green(bgPixel), bgAlpha, outAlpha);
                    int b = blendNormalChannel(source[sourceIndex + 2] & 255, fgAlpha, blue(bgPixel), bgAlpha, outAlpha);
                    canvas[cy * canvasWidth + cx] = argb(outAlpha, r, g, b);
                }
            }
        }

        private static void drawAdd(int[] canvas, int canvasWidth, int canvasHeight, byte[] source, int sourceWidth, int sourceHeight, int x, int y, RenderOptions options) {
            int startX = Math.max(0, x);
            int startY = Math.max(0, y);
            int endX = Math.min(canvasWidth, x + sourceWidth);
            int endY = Math.min(canvasHeight, y + sourceHeight);
            boolean preserveDestinationAlpha = isTransparentCanvas(options);
            for (int cy = startY; cy < endY; cy++) {
                for (int cx = startX; cx < endX; cx++) {
                    int sourceIndex = ((cy - y) * sourceWidth + (cx - x)) * 4;
                    int fgAlpha = source[sourceIndex + 3] & 255;
                    if (fgAlpha == 0) {
                        continue;
                    }
                    int canvasIndex = cy * canvasWidth + cx;
                    int bgPixel = canvas[canvasIndex];
                    int bgAlpha = alpha(bgPixel);
                    int fgRed = source[sourceIndex] & 255;
                    int fgGreen = source[sourceIndex + 1] & 255;
                    int fgBlue = source[sourceIndex + 2] & 255;
                    int outAlpha;
                    int r;
                    int g;
                    int b;
                    if (options.deferBackground && bgAlpha == 0) {
                        int backdropPixel = backgroundPixel(options, cx, cy);
                        int backdropAlpha = alpha(backdropPixel);
                        if (backdropAlpha == 0) {
                            if (isAddNoOp(fgRed, fgGreen, fgBlue)) {
                                continue;
                            }
                            outAlpha = fgAlpha;
                            r = fgRed;
                            g = fgGreen;
                            b = fgBlue;
                        } else {
                            outAlpha = blendNormalAlpha(fgAlpha, backdropAlpha);
                            r = blendAddChannel(fgRed, fgAlpha, red(backdropPixel), backdropAlpha, outAlpha);
                            g = blendAddChannel(fgGreen, fgAlpha, green(backdropPixel), backdropAlpha, outAlpha);
                            b = blendAddChannel(fgBlue, fgAlpha, blue(backdropPixel), backdropAlpha, outAlpha);
                            if (outAlpha == backdropAlpha && r == red(backdropPixel) && g == green(backdropPixel) && b == blue(backdropPixel)) {
                                continue;
                            }
                        }
                    } else if (preserveDestinationAlpha) {
                        outAlpha = bgAlpha;
                        if (outAlpha == 0) {
                            if (isAddNoOp(fgRed, fgGreen, fgBlue)) {
                                continue;
                            }
                            outAlpha = fgAlpha;
                            r = fgRed;
                            g = fgGreen;
                            b = fgBlue;
                        } else {
                            r = clamp(red(bgPixel) + fgRed);
                            g = clamp(green(bgPixel) + fgGreen);
                            b = clamp(blue(bgPixel) + fgBlue);
                        }
                    } else {
                        outAlpha = blendNormalAlpha(fgAlpha, bgAlpha);
                        r = blendAddChannel(fgRed, fgAlpha, red(bgPixel), bgAlpha, outAlpha);
                        g = blendAddChannel(fgGreen, fgAlpha, green(bgPixel), bgAlpha, outAlpha);
                        b = blendAddChannel(fgBlue, fgAlpha, blue(bgPixel), bgAlpha, outAlpha);
                    }
                    canvas[canvasIndex] = argb(outAlpha, r, g, b);
                }
            }
        }

        private static void drawAddOverlay(int[] canvas, int canvasWidth, int canvasHeight, byte[] source, int sourceWidth, int sourceHeight, int x, int y) {
            int startX = Math.max(0, x);
            int startY = Math.max(0, y);
            int endX = Math.min(canvasWidth, x + sourceWidth);
            int endY = Math.min(canvasHeight, y + sourceHeight);
            for (int cy = startY; cy < endY; cy++) {
                for (int cx = startX; cx < endX; cx++) {
                    int sourceIndex = ((cy - y) * sourceWidth + (cx - x)) * 4;
                    int fgAlpha = source[sourceIndex + 3] & 255;
                    if (fgAlpha == 0) {
                        continue;
                    }
                    int fgRed = source[sourceIndex] & 255;
                    int fgGreen = source[sourceIndex + 1] & 255;
                    int fgBlue = source[sourceIndex + 2] & 255;
                    if (isAddNoOp(fgRed, fgGreen, fgBlue)) {
                        continue;
                    }
                    int canvasIndex = cy * canvasWidth + cx;
                    int bgPixel = canvas[canvasIndex];
                    int bgAlpha = alpha(bgPixel);
                    if (bgAlpha == 0) {
                        canvas[canvasIndex] = argb(fgAlpha, fgRed, fgGreen, fgBlue);
                        continue;
                    }
                    int outAlpha = blendNormalAlpha(fgAlpha, bgAlpha);
                    int r = blendAddChannel(fgRed, fgAlpha, red(bgPixel), bgAlpha, outAlpha);
                    int g = blendAddChannel(fgGreen, fgAlpha, green(bgPixel), bgAlpha, outAlpha);
                    int b = blendAddChannel(fgBlue, fgAlpha, blue(bgPixel), bgAlpha, outAlpha);
                    canvas[canvasIndex] = argb(outAlpha, r, g, b);
                }
            }
        }

        private static boolean isAddNoOp(int r, int g, int b) {
            return r == 0 && g == 0 && b == 0;
        }

        private static int backgroundPixel(RenderOptions options, int x, int y) {
            if (options.backgroundRgba == null || options.backgroundWidth <= 0 || options.backgroundHeight <= 0
                    || x < 0 || y < 0 || x >= options.backgroundWidth || y >= options.backgroundHeight) {
                return parseCanvasColor(options.canvas);
            }
            int source = (y * options.backgroundWidth + x) * 4;
            if (source + 3 >= options.backgroundRgba.length) {
                return parseCanvasColor(options.canvas);
            }
            return argb(
                    options.backgroundRgba[source + 3] & 255,
                    options.backgroundRgba[source] & 255,
                    options.backgroundRgba[source + 1] & 255,
                    options.backgroundRgba[source + 2] & 255);
        }

        private static int blendNormalAlpha(int fgAlpha, int bgAlpha) {
            double sourceAlpha = fgAlpha / 255.0;
            double backgroundAlpha = bgAlpha / 255.0;
            return clamp((int) Math.round((sourceAlpha + backgroundAlpha * (1.0 - sourceAlpha)) * 255.0));
        }

        private static int blendNormalChannel(int fg, int fgAlpha, int bg, int bgAlpha, int alpha) {
            if (alpha == 0) {
                return 0;
            }
            double sourceAlpha = fgAlpha / 255.0;
            double backgroundAlpha = bgAlpha / 255.0;
            double outAlpha = alpha / 255.0;
            return clamp((int) Math.round((fg * sourceAlpha + bg * backgroundAlpha * (1.0 - sourceAlpha)) / outAlpha));
        }

        private static int blendAddChannel(int fg, int fgAlpha, int bg, int bgAlpha, int alpha) {
            if (alpha == 0) {
                return 0;
            }
            double sourceAlpha = fgAlpha / 255.0;
            double backgroundAlpha = bgAlpha / 255.0;
            double outAlpha = alpha / 255.0;
            double blended = Math.min(255, fg + bg);
            double blendWeight = backgroundAlpha * sourceAlpha;
            double backgroundWeight = backgroundAlpha - blendWeight;
            double sourceWeight = sourceAlpha - blendWeight;
            return clamp((int) Math.round((bg * backgroundWeight + fg * sourceWeight + blended * blendWeight) / outAlpha));
        }

        private static byte[] flipHorizontal(byte[] source, int width, int height) {
            byte[] result = new byte[source.length];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int sourceIndex = (y * width + x) * 4;
                    int targetIndex = (y * width + (width - 1 - x)) * 4;
                    copyPixel(source, sourceIndex, result, targetIndex);
                }
            }
            return result;
        }

        private static byte[] applyAlpha(byte[] source, int alpha) {
            byte[] result = copyBytes(source);
            for (int i = 3; i < result.length; i += 4) {
                if ((result[i] & 255) > 0) {
                    result[i] = (byte) alpha;
                }
            }
            return result;
        }

        private static byte[] applyTint(byte[] source, String color, int alpha) {
            int rgb = parseRgb(color);
            byte[] result = copyBytes(source);
            int tr = red(rgb);
            int tg = green(rgb);
            int tb = blue(rgb);
            for (int i = 0; i < result.length; i += 4) {
                if ((result[i + 3] & 255) == 0) {
                    continue;
                }
                result[i] = (byte) ((tr * (result[i] & 255)) / 255);
                result[i + 1] = (byte) ((tg * (result[i + 1] & 255)) / 255);
                result[i + 2] = (byte) ((tb * (result[i + 2] & 255)) / 255);
                result[i + 3] = (byte) alpha;
            }
            return result;
        }

        private static byte[] applyOpacity(byte[] source, double opacity) {
            byte[] result = copyBytes(source);
            for (int i = 3; i < result.length; i += 4) {
                result[i] = (byte) Math.round((result[i] & 255) * opacity);
            }
            return result;
        }

        private static void fillCanvas(int[] canvas, int width, int height, RenderOptions options) {
            if (options.background && !options.deferBackground && options.backgroundRgba != null && options.backgroundRgba.length >= width * height * 4) {
                for (int i = 0; i < width * height; i++) {
                    int source = i * 4;
                    canvas[i] = argb(
                            options.backgroundRgba[source + 3] & 255,
                            options.backgroundRgba[source] & 255,
                            options.backgroundRgba[source + 1] & 255,
                            options.backgroundRgba[source + 2] & 255);
                }
            } else {
                int pixel = parseCanvasColor(options.canvas);
                for (int i = 0; i < canvas.length; i++) {
                    canvas[i] = pixel;
                }
            }
        }

        private static Bounds frameCropBounds(RenderedFrame frame, int width, int height, int[] trimColors) {
            Bounds result = hasNonTrimPixel(frame.canvas, trimColors) ? cropBounds(frame.canvas, width, height, trimColors) : null;
            if (frame.addCanvas != null) {
                int[] transparent = new int[] { parseCanvasColor("transparent") };
                if (hasNonTrimPixel(frame.addCanvas, transparent)) {
                    Bounds addBounds = cropBounds(frame.addCanvas, width, height, transparent);
                    result = result == null ? addBounds : result.union(addBounds);
                }
            }
            return result;
        }

        private static boolean hasAddOverlay(RenderedFrame[] frames) {
            int[] transparent = new int[] { parseCanvasColor("transparent") };
            for (int i = 0; i < frames.length; i++) {
                if (frames[i].addCanvas != null && hasNonTrimPixel(frames[i].addCanvas, transparent)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean hasNonTrimPixel(int[] canvas, int[] trimColors) {
            for (int i = 0; i < canvas.length; i++) {
                if (!sameAny(canvas[i], trimColors)) {
                    return true;
                }
            }
            return false;
        }

        private static Bounds cropBounds(int[] canvas, int width, int height, int[] trimColors) {
            int top = 0;
            for (int y = 0; y < height; y++) {
                if (!matchingRow(canvas, width, y, trimColors)) {
                    break;
                }
                top = y;
            }
            int bottom = 0;
            for (int y = height - 1; y >= 0; y--) {
                if (!matchingRow(canvas, width, y, trimColors)) {
                    break;
                }
                bottom = y;
            }
            int left = 0;
            for (int x = 0; x < width; x++) {
                if (!matchingColumn(canvas, width, height, x, trimColors)) {
                    break;
                }
                left = x;
            }
            int right = 0;
            for (int x = width - 1; x >= 0; x--) {
                if (!matchingColumn(canvas, width, height, x, trimColors)) {
                    break;
                }
                right = x;
            }
            if (right == 0) {
                right = width;
            }
            if (bottom == 0) {
                bottom = height;
            }
            int cropWidth = right - left;
            int cropHeight = bottom - top;
            if (cropWidth == 0) {
                left = 0;
                cropWidth = width;
            }
            if (cropHeight == 0) {
                top = 0;
                cropHeight = height;
            }
            return new Bounds(left, top, cropWidth, cropHeight);
        }

        private static boolean matchingRow(int[] canvas, int width, int y, int[] colors) {
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                if (!sameAny(canvas[offset + x], colors)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean matchingColumn(int[] canvas, int width, int height, int x, int[] colors) {
            for (int y = 0; y < height; y++) {
                if (!sameAny(canvas[y * width + x], colors)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean sameAny(int pixel, int[] colors) {
            for (int i = 0; i < colors.length; i++) {
                if (pixel == colors[i]) {
                    return true;
                }
            }
            return false;
        }

        private static int[] trimColors(RenderOptions options) {
            if (options.deferBackground) {
                return new int[] { parseCanvasColor("transparent") };
            }
            if (options.background) {
                return new int[] {
                        argb(255, 142, 142, 94),
                        argb(255, 152, 152, 101)
                };
            }
            return new int[] { parseCanvasColor(options.canvas) };
        }

        private static byte[] cropRgba(int[] canvas, int canvasWidth, Bounds crop) {
            byte[] rgba = new byte[crop.width * crop.height * 4];
            for (int y = 0; y < crop.height; y++) {
                for (int x = 0; x < crop.width; x++) {
                    int pixel = canvas[(crop.y + y) * canvasWidth + crop.x + x];
                    int target = (y * crop.width + x) * 4;
                    int a = alpha(pixel);
                    int r = red(pixel);
                    int g = green(pixel);
                    int b = blue(pixel);
                    if (a > 0 && a < 255) {
                        r = quantizeSystemDrawingChannel(r, a);
                        g = quantizeSystemDrawingChannel(g, a);
                        b = quantizeSystemDrawingChannel(b, a);
                    }
                    rgba[target] = (byte) r;
                    rgba[target + 1] = (byte) g;
                    rgba[target + 2] = (byte) b;
                    rgba[target + 3] = (byte) a;
                }
            }
            return rgba;
        }

        private static int quantizeSystemDrawingChannel(int channel, int alpha) {
            return (int) ((Math.round(channel * alpha / 255.0) * 255.0) / alpha);
        }

        private static boolean isTransparentCanvas(RenderOptions options) {
            return options.deferBackground || (!options.background && alpha(parseCanvasColor(options.canvas)) == 0);
        }

        private static int parseCanvasColor(String value) {
            if ("transparent".equalsIgnoreCase(value)) {
                return argb(0, 0, 0, 0);
            }
            int rgb = parseRgb(value);
            return argb(255, red(rgb), green(rgb), blue(rgb));
        }

        private static int parseRgb(String value) {
            String hex = value == null ? "" : value;
            if (hex.startsWith("#")) {
                hex = hex.substring(1);
            }
            try {
                if (hex.length() == 3) {
                    int r = Integer.parseInt(hex.substring(0, 1) + hex.substring(0, 1), 16);
                    int g = Integer.parseInt(hex.substring(1, 2) + hex.substring(1, 2), 16);
                    int b = Integer.parseInt(hex.substring(2, 3) + hex.substring(2, 3), 16);
                    return argb(255, r, g, b);
                }
                if (hex.length() == 6) {
                    int r = Integer.parseInt(hex.substring(0, 2), 16);
                    int g = Integer.parseInt(hex.substring(2, 4), 16);
                    int b = Integer.parseInt(hex.substring(4, 6), 16);
                    return argb(255, r, g, b);
                }
            } catch (RuntimeException ignored) {
                return argb(255, 254, 254, 254);
            }
            return argb(255, 254, 254, 254);
        }

        private static XmlNode firstChild(XmlNode node, String name) {
            List<XmlNode> result = children(node, name);
            return result.isEmpty() ? null : result.get(0);
        }

        private static List<XmlNode> children(XmlNode node, String name) {
            List<XmlNode> result = new ArrayList<>();
            if (node == null) {
                return result;
            }
            for (int i = 0; i < node.children.size(); i++) {
                XmlNode child = node.children.get(i);
                if (name.equals(child.name)) {
                    result.add(child);
                }
            }
            return result;
        }

        private static String normalizeName(String sprite, String name) {
            if (name == null) {
                return "";
            }
            String prefix = sprite + "_";
            return name.startsWith(prefix) ? name.substring(prefix.length()) : name;
        }

        private static List<String> split(String value, char delimiter) {
            List<String> result = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < value.length(); i++) {
                if (value.charAt(i) == delimiter) {
                    result.add(value.substring(start, i));
                    start = i + 1;
                }
            }
            result.add(value.substring(start));
            return result;
        }

        private static int parseInt(String value, int fallback) {
            try {
                return Integer.parseInt(value);
            } catch (RuntimeException ex) {
                return fallback;
            }
        }

        private static int argb(int a, int r, int g, int b) {
            return ((a & 255) << 24) | ((r & 255) << 16) | ((g & 255) << 8) | (b & 255);
        }

        private static int alpha(int pixel) {
            return (pixel >>> 24) & 255;
        }

        private static int red(int pixel) {
            return (pixel >>> 16) & 255;
        }

        private static int green(int pixel) {
            return (pixel >>> 8) & 255;
        }

        private static int blue(int pixel) {
            return pixel & 255;
        }

        private static int clamp(int value) {
            return Math.max(0, Math.min(255, value));
        }

        private static byte[] copyBytes(byte[] source) {
            byte[] result = new byte[source.length];
            System.arraycopy(source, 0, result, 0, source.length);
            return result;
        }

        private static void copyPixel(byte[] source, int sourceIndex, byte[] target, int targetIndex) {
            target[targetIndex] = source[sourceIndex];
            target[targetIndex + 1] = source[sourceIndex + 1];
            target[targetIndex + 2] = source[sourceIndex + 2];
            target[targetIndex + 3] = source[sourceIndex + 3];
        }

        private static void sortAssets(List<RenderAsset> assets) {
            for (int i = 1; i < assets.size(); i++) {
                RenderAsset current = assets.get(i);
                int j = i - 1;
                while (j >= 0 && assets.get(j).z > current.z) {
                    assets.set(j + 1, assets.get(j));
                    j--;
                }
                assets.set(j + 1, current);
            }
        }
    }

    private static final class ImageEntry {
        private final ImageAsset image;
        private final boolean flipH;

        private ImageEntry(ImageAsset image, boolean flipH) {
            this.image = image;
            this.flipH = flipH;
        }
    }

    private static final class RenderAsset {
        private String name = "";
        private ImageAsset image;
        private boolean flipH;
        private int x;
        private int y;
        private int z;
        private int layer;
        private int direction;
        private int frame;
        private String ink = "";
        private int alpha = -1;
        private String color = "";
        private boolean shadow;
    }

    private static final class LayerInfo {
        private String ink = "";
        private int z;
        private boolean zSet;
        private int alpha = -1;
    }

    private static final class AnimationSequence {
        private final int loopCount;
        private final List<AnimationFrameRef> frames = new ArrayList<>();

        private AnimationSequence(int loopCount) {
            this.loopCount = loopCount;
        }

        private int frameCount() {
            return frames.size() * loopCount;
        }

        private int availableFrameCount() {
            return frames.size();
        }

        private AnimationFrameRef frame(int index, int direction) {
            if (frames.isEmpty() || index < 0 || index >= frameCount()) {
                return null;
            }
            return frames.get(index % frames.size()).forDirection(direction);
        }

        private AnimationFrameRef availableFrame(int index, int direction) {
            if (frames.isEmpty() || index < 0 || index >= frames.size()) {
                return null;
            }
            return frames.get(index).forDirection(direction);
        }
    }

    private static final class AnimationFrameRef {
        private final int id;
        private final int x;
        private final int y;
        private final Map<Integer, int[]> offsets = new LinkedHashMap<>();

        private AnimationFrameRef(int id, int x, int y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }

        private AnimationFrameRef forDirection(int direction) {
            int[] offset = offsets.get(direction);
            if (offset == null) {
                return this;
            }
            return new AnimationFrameRef(id, offset[0], offset[1]);
        }
    }

    private static final class ParsedAsset {
        private String size;
        private int layer;
        private int direction;
        private int frame;
    }

    private static final class Bounds {
        private final int x;
        private final int y;
        private final int width;
        private final int height;

        private Bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        private Bounds union(Bounds other) {
            int left = Math.min(x, other.x);
            int top = Math.min(y, other.y);
            int right = Math.max(x + width, other.x + other.width);
            int bottom = Math.max(y + height, other.y + other.height);
            return new Bounds(left, top, right - left, bottom - top);
        }
    }

    private static final class RenderedFrame {
        private final int[] canvas;
        private final int[] addCanvas;

        private RenderedFrame(int[] canvas, int[] addCanvas) {
            this.canvas = canvas;
            this.addCanvas = addCanvas;
        }
    }

    private static final class XmlNode {
        private final String name;
        private final Map<String, String> attrs = new LinkedHashMap<>();
        private final List<XmlNode> children = new ArrayList<>();

        private XmlNode(String name) {
            this.name = name;
        }

        private String attr(String name) {
            String value = attrs.get(name);
            return value == null ? "" : value;
        }

        private boolean hasAttr(String name) {
            return attrs.containsKey(name);
        }

        private List<XmlNode> descendants(String name) {
            List<XmlNode> result = new ArrayList<>();
            collectDescendants(this, name, result);
            return result;
        }

        private static void collectDescendants(XmlNode node, String name, List<XmlNode> result) {
            for (int i = 0; i < node.children.size(); i++) {
                XmlNode child = node.children.get(i);
                if (name.equals(child.name)) {
                    result.add(child);
                }
                collectDescendants(child, name, result);
            }
        }

        private static XmlNode parse(String xml) throws IOException {
            XmlNode root = new XmlNode("#document");
            List<XmlNode> stack = new ArrayList<>();
            stack.add(root);
            int i = 0;
            while (i < xml.length()) {
                int open = xml.indexOf('<', i);
                if (open < 0) {
                    break;
                }
                if (startsWith(xml, open, "<!--")) {
                    int end = xml.indexOf("-->", open + 4);
                    i = end < 0 ? xml.length() : end + 3;
                    continue;
                }
                if (startsWith(xml, open, "<?")) {
                    int end = xml.indexOf("?>", open + 2);
                    i = end < 0 ? xml.length() : end + 2;
                    continue;
                }
                if (startsWith(xml, open, "</")) {
                    int end = xml.indexOf('>', open + 2);
                    if (end < 0) {
                        throw new IOException("Malformed XML end tag");
                    }
                    if (stack.size() > 1) {
                        stack.remove(stack.size() - 1);
                    }
                    i = end + 1;
                    continue;
                }
                int end = findTagEnd(xml, open + 1);
                if (end < 0) {
                    throw new IOException("Malformed XML start tag");
                }
                boolean selfClosing = end > open && xml.charAt(end - 1) == '/';
                String body = xml.substring(open + 1, selfClosing ? end - 1 : end).trim();
                if (body.length() == 0) {
                    i = end + 1;
                    continue;
                }
                XmlNode node = parseNode(body);
                stack.get(stack.size() - 1).children.add(node);
                if (!selfClosing) {
                    stack.add(node);
                }
                i = end + 1;
            }
            return root;
        }

        private static XmlNode parseNode(String body) {
            int index = 0;
            while (index < body.length() && !isSpace(body.charAt(index))) {
                index++;
            }
            XmlNode node = new XmlNode(body.substring(0, index));
            while (index < body.length()) {
                while (index < body.length() && isSpace(body.charAt(index))) {
                    index++;
                }
                if (index >= body.length()) {
                    break;
                }
                int nameStart = index;
                while (index < body.length() && body.charAt(index) != '=' && !isSpace(body.charAt(index))) {
                    index++;
                }
                String attrName = body.substring(nameStart, index);
                while (index < body.length() && isSpace(body.charAt(index))) {
                    index++;
                }
                if (index >= body.length() || body.charAt(index) != '=') {
                    break;
                }
                index++;
                while (index < body.length() && isSpace(body.charAt(index))) {
                    index++;
                }
                if (index >= body.length()) {
                    break;
                }
                char quote = body.charAt(index);
                if (quote != '"' && quote != '\'') {
                    break;
                }
                index++;
                int valueStart = index;
                while (index < body.length() && body.charAt(index) != quote) {
                    index++;
                }
                String value = body.substring(valueStart, Math.min(index, body.length()));
                node.attrs.put(attrName, Xml.decode(value));
                if (index < body.length()) {
                    index++;
                }
            }
            return node;
        }

        private static int findTagEnd(String xml, int start) {
            char quote = 0;
            for (int i = start; i < xml.length(); i++) {
                char c = xml.charAt(i);
                if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    }
                } else if (c == '"' || c == '\'') {
                    quote = c;
                } else if (c == '>') {
                    return i;
                }
            }
            return -1;
        }

        private static boolean startsWith(String value, int offset, String prefix) {
            return offset + prefix.length() <= value.length() && value.substring(offset, offset + prefix.length()).equals(prefix);
        }

        private static boolean isSpace(char c) {
            return c == ' ' || c == '\n' || c == '\r' || c == '\t';
        }
    }

    private static final class GifEncoder {
        private static byte[] encode(int width, int height, byte[][] frames, int delayMs, boolean loop) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeAscii(out, "GIF89a");
            writeShort(out, width);
            writeShort(out, height);
            out.write(0);
            out.write(0);
            out.write(0);
            if (loop) {
                out.write(0x21);
                out.write(0xff);
                out.write(11);
                writeAscii(out, "NETSCAPE2.0");
                out.write(3);
                out.write(1);
                writeShort(out, 0);
                out.write(0);
            }

            for (int i = 0; i < frames.length; i++) {
                Palette palette = Palette.fromFrame(frames[i]);
                byte[] indexed = palette.index(frames[i]);
                out.write(0x21);
                out.write(0xf9);
                out.write(4);
                out.write(9);
                writeShort(out, Math.max(1, delayMs / 10));
                out.write(0);
                out.write(0);
                out.write(0x2c);
                writeShort(out, 0);
                writeShort(out, 0);
                writeShort(out, width);
                writeShort(out, height);
                out.write(0x87);
                for (int c = 0; c < 256; c++) {
                    int color = palette.colors[c];
                    out.write((color >>> 16) & 255);
                    out.write((color >>> 8) & 255);
                    out.write(color & 255);
                }
                out.write(8);
                writeSubBlocks(out, lzwEncode(indexed));
            }
            out.write(0x3b);
            return out.toByteArray();
        }

        private static byte[] lzwEncode(byte[] indexed) {
            ByteArrayOutputStream bits = new ByteArrayOutputStream();
            BitWriter writer = new BitWriter(bits);
            int clear = 256;
            int end = 257;
            int codeSize = 9;
            writer.write(clear, codeSize);
            int codesSinceClear = 0;
            for (int i = 0; i < indexed.length; i++) {
                if (codesSinceClear >= 240) {
                    writer.write(clear, codeSize);
                    codesSinceClear = 0;
                }
                writer.write(indexed[i] & 255, codeSize);
                codesSinceClear++;
            }
            writer.write(end, codeSize);
            writer.flush();
            return bits.toByteArray();
        }

        private static void writeSubBlocks(ByteArrayOutputStream out, byte[] bytes) {
            for (int i = 0; i < bytes.length; i += 255) {
                int length = Math.min(255, bytes.length - i);
                out.write(length);
                out.write(bytes, i, length);
            }
            out.write(0);
        }

        private static void writeShort(ByteArrayOutputStream out, int value) {
            out.write(value & 255);
            out.write((value >>> 8) & 255);
        }

        private static void writeAscii(ByteArrayOutputStream out, String value) {
            for (int i = 0; i < value.length(); i++) {
                out.write(value.charAt(i));
            }
        }

        private static final class BitWriter {
            private final ByteArrayOutputStream out;
            private int buffer;
            private int bits;

            private BitWriter(ByteArrayOutputStream out) {
                this.out = out;
            }

            private void write(int code, int size) {
                buffer |= code << bits;
                bits += size;
                while (bits >= 8) {
                    out.write(buffer & 255);
                    buffer >>>= 8;
                    bits -= 8;
                }
            }

            private void flush() {
                if (bits > 0) {
                    out.write(buffer & 255);
                }
            }
        }

        private static final class Palette {
            private static final int TRANSPARENT_KEY = -1;
            private static final int TRANSPARENT_COLOR = 0xFF00FF;
            private static final int MAX_VISIBLE_COLORS = 255;

            private final int[] colors = new int[256];
            private final Map<Integer, Integer> exact = new LinkedHashMap<>();
            private final Map<Integer, Integer> nearest = new LinkedHashMap<>();
            private boolean exactOnly = true;

            private static Palette fromFrame(byte[] frame) {
                Map<Integer, Integer> counts = colorCounts(frame);
                Palette palette = new Palette();
                palette.colors[0] = TRANSPARENT_COLOR;
                palette.exact.put(TRANSPARENT_KEY, 0);

                if (counts.size() <= MAX_VISIBLE_COLORS) {
                    int next = 1;
                    for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
                        int color = entry.getKey();
                        palette.exact.put(color, next);
                        palette.colors[next++] = color;
                    }
                    return palette;
                }

                palette.exactOnly = false;
                List<ColorCount> colorCounts = new ArrayList<>();
                for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
                    colorCounts.add(new ColorCount(entry.getKey(), entry.getValue()));
                }
                List<ColorBox> boxes = medianCut(colorCounts);
                for (int i = 0; i < boxes.size() && i < MAX_VISIBLE_COLORS; i++) {
                    palette.colors[i + 1] = boxes.get(i).averageColor();
                }
                return palette;
            }

            private static Map<Integer, Integer> colorCounts(byte[] frame) {
                Map<Integer, Integer> counts = new LinkedHashMap<>();
                for (int i = 0; i < frame.length; i += 4) {
                    int a = frame[i + 3] & 255;
                    if (a == 0) {
                        continue;
                    }
                    int color = ((frame[i] & 255) << 16) | ((frame[i + 1] & 255) << 8) | (frame[i + 2] & 255);
                    Integer count = counts.get(color);
                    counts.put(color, count == null ? 1 : count + 1);
                }
                return counts;
            }

            private static List<ColorBox> medianCut(List<ColorCount> colors) {
                List<ColorBox> boxes = new ArrayList<>();
                boxes.add(new ColorBox(colors));
                while (boxes.size() < MAX_VISIBLE_COLORS) {
                    int splitIndex = -1;
                    long splitScore = -1;
                    for (int i = 0; i < boxes.size(); i++) {
                        ColorBox box = boxes.get(i);
                        if (box.colors.size() <= 1) {
                            continue;
                        }
                        long score = (long) box.range() * box.totalCount;
                        if (score > splitScore) {
                            splitScore = score;
                            splitIndex = i;
                        }
                    }
                    if (splitIndex < 0) {
                        break;
                    }
                    ColorBox box = boxes.remove(splitIndex);
                    ColorBox[] split = box.split();
                    if (split == null) {
                        boxes.add(box);
                        break;
                    }
                    boxes.add(split[0]);
                    boxes.add(split[1]);
                }
                return boxes;
            }

            private byte[] index(byte[] rgba) {
                byte[] result = new byte[rgba.length / 4];
                for (int i = 0, p = 0; i < rgba.length; i += 4, p++) {
                    int a = rgba[i + 3] & 255;
                    if (a == 0) {
                        result[p] = 0;
                    } else {
                        int color = ((rgba[i] & 255) << 16) | ((rgba[i + 1] & 255) << 8) | (rgba[i + 2] & 255);
                        Integer exactIndex = exactOnly ? exact.get(color) : null;
                        result[p] = (byte) (exactIndex != null ? exactIndex : nearestIndex(color));
                    }
                }
                return result;
            }

            private int nearestIndex(int color) {
                Integer cached = nearest.get(color);
                if (cached != null) {
                    return cached;
                }
                int r = (color >>> 16) & 255;
                int g = (color >>> 8) & 255;
                int b = color & 255;
                int bestIndex = 1;
                int bestDistance = Integer.MAX_VALUE;
                for (int i = 1; i < 256; i++) {
                    int paletteColor = colors[i];
                    int dr = r - ((paletteColor >>> 16) & 255);
                    int dg = g - ((paletteColor >>> 8) & 255);
                    int db = b - (paletteColor & 255);
                    int distance = dr * dr + dg * dg + db * db;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestIndex = i;
                        if (distance == 0) {
                            break;
                        }
                    }
                }
                nearest.put(color, bestIndex);
                return bestIndex;
            }
        }

        private static final class ColorCount {
            private final int color;
            private final int count;

            private ColorCount(int color, int count) {
                this.color = color;
                this.count = count;
            }
        }

        private static final class ColorBox {
            private final List<ColorCount> colors;
            private int minR;
            private int maxR;
            private int minG;
            private int maxG;
            private int minB;
            private int maxB;
            private int totalCount;

            private ColorBox(List<ColorCount> colors) {
                this.colors = colors;
                recalculate();
            }

            private void recalculate() {
                minR = minG = minB = 255;
                maxR = maxG = maxB = 0;
                totalCount = 0;
                for (int i = 0; i < colors.size(); i++) {
                    ColorCount colorCount = colors.get(i);
                    int color = colorCount.color;
                    int r = (color >>> 16) & 255;
                    int g = (color >>> 8) & 255;
                    int b = color & 255;
                    if (r < minR) minR = r;
                    if (r > maxR) maxR = r;
                    if (g < minG) minG = g;
                    if (g > maxG) maxG = g;
                    if (b < minB) minB = b;
                    if (b > maxB) maxB = b;
                    totalCount += colorCount.count;
                }
            }

            private int range() {
                int r = maxR - minR;
                int g = maxG - minG;
                int b = maxB - minB;
                return Math.max(r, Math.max(g, b));
            }

            private ColorBox[] split() {
                if (colors.size() <= 1) {
                    return null;
                }
                int channel = splitChannel();
                sortByChannel(colors, channel);
                int midpoint = Math.max(1, totalCount / 2);
                int running = 0;
                int splitAt = 1;
                for (int i = 0; i < colors.size(); i++) {
                    running += colors.get(i).count;
                    if (running >= midpoint) {
                        splitAt = i + 1;
                        break;
                    }
                }
                if (splitAt <= 0 || splitAt >= colors.size()) {
                    splitAt = colors.size() / 2;
                }
                if (splitAt <= 0 || splitAt >= colors.size()) {
                    return null;
                }
                List<ColorCount> left = new ArrayList<>();
                List<ColorCount> right = new ArrayList<>();
                for (int i = 0; i < splitAt; i++) {
                    left.add(colors.get(i));
                }
                for (int i = splitAt; i < colors.size(); i++) {
                    right.add(colors.get(i));
                }
                return new ColorBox[] { new ColorBox(left), new ColorBox(right) };
            }

            private int splitChannel() {
                int r = maxR - minR;
                int g = maxG - minG;
                int b = maxB - minB;
                if (r >= g && r >= b) {
                    return 0;
                }
                return g >= b ? 1 : 2;
            }

            private int averageColor() {
                long r = 0;
                long g = 0;
                long b = 0;
                long count = 0;
                for (int i = 0; i < colors.size(); i++) {
                    ColorCount colorCount = colors.get(i);
                    int color = colorCount.color;
                    r += (long) ((color >>> 16) & 255) * colorCount.count;
                    g += (long) ((color >>> 8) & 255) * colorCount.count;
                    b += (long) (color & 255) * colorCount.count;
                    count += colorCount.count;
                }
                if (count == 0) {
                    return 0;
                }
                int rr = (int) Math.round(r / (double) count);
                int gg = (int) Math.round(g / (double) count);
                int bb = (int) Math.round(b / (double) count);
                return (rr << 16) | (gg << 8) | bb;
            }

            private static void sortByChannel(List<ColorCount> colors, int channel) {
                colors.sort(new Comparator<ColorCount>() {
                    @Override
                    public int compare(ColorCount left, ColorCount right) {
                        return channel(left.color, channel) - channel(right.color, channel);
                    }
                });
            }

            private static int channel(int color, int channel) {
                if (channel == 0) {
                    return (color >>> 16) & 255;
                }
                if (channel == 1) {
                    return (color >>> 8) & 255;
                }
                return color & 255;
            }
        }
    }

    private static final class PngEncoder {
        private static byte[] encode(int width, int height, byte[] rgba) {
            byte[] compressed = zlibStore(scanlines(width, height, rgba));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            write(out, new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            chunk(out, "IHDR", pngHeader(width, height));
            chunk(out, "IDAT", compressed);
            chunk(out, "IEND", new byte[0]);
            return out.toByteArray();
        }

        static byte[] scanlines(int width, int height, byte[] rgba) {
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

        static byte[] pngHeader(int width, int height) {
            byte[] header = new byte[13];
            writeUint32(header, 0, width);
            writeUint32(header, 4, height);
            header[8] = 8;
            header[9] = 6;
            return header;
        }

        static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
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

        static byte[] zlibStore(byte[] data) {
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

        static int crc32(byte[] bytes) {
            int crc = -1;
            for (int i = 0; i < bytes.length; i++) {
                crc = crc ^ (bytes[i] & 255);
                for (int k = 0; k < 8; k++) {
                    crc = (crc & 1) != 0 ? 0xedb88320 ^ (crc >>> 1) : crc >>> 1;
                }
            }
            return crc ^ -1;
        }

        static int adler32(byte[] bytes) {
            int a = 1;
            int b = 0;
            for (int i = 0; i < bytes.length; i++) {
                a = (a + (bytes[i] & 255)) % 65521;
                b = (b + a) % 65521;
            }
            return (b << 16) | a;
        }

        static void writeUint32(byte[] bytes, int offset, int value) {
            bytes[offset] = (byte) ((value >>> 24) & 255);
            bytes[offset + 1] = (byte) ((value >>> 16) & 255);
            bytes[offset + 2] = (byte) ((value >>> 8) & 255);
            bytes[offset + 3] = (byte) (value & 255);
        }

        static void write(ByteArrayOutputStream out, byte[] bytes) {
            out.write(bytes, 0, bytes.length);
        }
    }

    private static final class ApngEncoder {
        private static byte[] encode(int width, int height, byte[][] frames, int delayMs, boolean loop) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PngEncoder.write(out, new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            PngEncoder.chunk(out, "IHDR", PngEncoder.pngHeader(width, height));

            byte[] animationControl = new byte[8];
            PngEncoder.writeUint32(animationControl, 0, frames.length);
            PngEncoder.writeUint32(animationControl, 4, loop ? 0 : 1);
            PngEncoder.chunk(out, "acTL", animationControl);

            int sequence = 0;
            for (int i = 0; i < frames.length; i++) {
                PngEncoder.chunk(out, "fcTL", frameControl(sequence++, width, height, delayMs));
                byte[] compressed = PngEncoder.zlibStore(PngEncoder.scanlines(width, height, frames[i]));
                if (i == 0) {
                    PngEncoder.chunk(out, "IDAT", compressed);
                } else {
                    byte[] frameData = new byte[compressed.length + 4];
                    PngEncoder.writeUint32(frameData, 0, sequence++);
                    System.arraycopy(compressed, 0, frameData, 4, compressed.length);
                    PngEncoder.chunk(out, "fdAT", frameData);
                }
            }

            PngEncoder.chunk(out, "IEND", new byte[0]);
            return out.toByteArray();
        }

        private static byte[] frameControl(int sequence, int width, int height, int delayMs) {
            byte[] frameControl = new byte[26];
            PngEncoder.writeUint32(frameControl, 0, sequence);
            PngEncoder.writeUint32(frameControl, 4, width);
            PngEncoder.writeUint32(frameControl, 8, height);
            PngEncoder.writeUint32(frameControl, 12, 0);
            PngEncoder.writeUint32(frameControl, 16, 0);
            writeUint16(frameControl, 20, Math.max(1, Math.min(65535, delayMs)));
            writeUint16(frameControl, 22, 1000);
            frameControl[24] = 0;
            frameControl[25] = 0;
            return frameControl;
        }

        private static void writeUint16(byte[] bytes, int offset, int value) {
            bytes[offset] = (byte) ((value >>> 8) & 255);
            bytes[offset + 1] = (byte) (value & 255);
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

        private static boolean hasKey(String json, String key) {
            return keyOffset(json, key) >= 0;
        }

        private static String readString(String json, String key, String fallback) {
            int offset = valueOffset(json, key);
            if (offset < 0 || offset >= json.length() || json.charAt(offset) != '"') {
                return fallback;
            }
            StringBuilder out = new StringBuilder();
            for (int i = offset + 1; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\' && i + 1 < json.length()) {
                    char escaped = json.charAt(++i);
                    if (escaped == 'n') {
                        out.append('\n');
                    } else if (escaped == 'r') {
                        out.append('\r');
                    } else if (escaped == 't') {
                        out.append('\t');
                    } else {
                        out.append(escaped);
                    }
                } else {
                    out.append(c);
                }
            }
            return fallback;
        }

        private static int readInt(String json, String key, int fallback) {
            int offset = valueOffset(json, key);
            if (offset < 0) {
                return fallback;
            }
            int end = offset;
            while (end < json.length()) {
                char c = json.charAt(end);
                if ((c < '0' || c > '9') && c != '-') {
                    break;
                }
                end++;
            }
            try {
                return Integer.parseInt(json.substring(offset, end));
            } catch (RuntimeException ex) {
                return fallback;
            }
        }

        private static boolean readBoolean(String json, String key, boolean fallback) {
            int offset = valueOffset(json, key);
            if (offset < 0) {
                return fallback;
            }
            if (offset + 4 <= json.length() && "true".equals(json.substring(offset, offset + 4))) {
                return true;
            }
            if (offset + 5 <= json.length() && "false".equals(json.substring(offset, offset + 5))) {
                return false;
            }
            if (offset < json.length() && json.charAt(offset) == '"') {
                String value = readString(json, key, String.valueOf(fallback));
                return "true".equalsIgnoreCase(value);
            }
            return fallback;
        }

        private static int valueOffset(String json, String key) {
            int keyOffset = keyOffset(json, key);
            if (keyOffset < 0) {
                return -1;
            }
            int colon = json.indexOf(':', keyOffset);
            if (colon < 0) {
                return -1;
            }
            int offset = colon + 1;
            while (offset < json.length()) {
                char c = json.charAt(offset);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                    break;
                }
                offset++;
            }
            return offset;
        }

        private static int keyOffset(String json, String key) {
            return json.indexOf("\"" + key + "\"");
        }
    }

    private static final class Xml {
        private static String decode(String value) {
            return value
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&amp;", "&");
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
