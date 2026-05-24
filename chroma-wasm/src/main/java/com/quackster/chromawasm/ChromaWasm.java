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

    @JSExport
    public static String renderSwfBase64(String base64, String optionsJson) {
        try {
            RenderOptions options = RenderOptions.parse(optionsJson == null ? "{}" : optionsJson);
            byte[] bytes = Base64Codec.decode(base64);
            FurniPackage furniPackage = SwfExtractor.extract(bytes, options.sprite);
            RenderResult result = WasmRenderer.render(furniPackage, options);
            return "{\"ok\":true,\"width\":" + result.width
                    + ",\"height\":" + result.height
                    + ",\"pngBase64\":\"" + Base64Codec.encode(result.png) + "\"}";
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
        private int backgroundWidth;
        private int backgroundHeight;
        private byte[] backgroundRgba;

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
        private final byte[] png;

        private RenderResult(int width, int height, byte[] png) {
            this.width = width;
            this.height = height;
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
            List<RenderAsset> renderAssets = collectWithDirectionFallback(furni.sprite, assetsXml, visualizationXml, images, options, renderWidth, renderHeight);
            int[] canvas = new int[renderWidth * renderHeight];
            fillCanvas(canvas, renderWidth, renderHeight, options);
            for (int i = 0; i < renderAssets.size(); i++) {
                drawAsset(canvas, renderWidth, renderHeight, renderAssets.get(i), options);
            }

            Bounds crop = options.crop ? cropBounds(canvas, renderWidth, renderHeight, trimColors(options)) : new Bounds(0, 0, renderWidth, renderHeight);
            byte[] rgba = cropRgba(canvas, renderWidth, crop);
            return new RenderResult(crop.width, crop.height, PngEncoder.encode(crop.width, crop.height, rgba));
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
                int renderHeight) throws IOException {

            List<RenderAsset> preferred = collectRenderAssets(sprite, assetsXml, visualizationXml, images, options, options.direction, renderWidth, renderHeight);
            if (!preferred.isEmpty() || options.icon) {
                return preferred;
            }
            int[] directions = {0, 2, 4, 6};
            for (int i = 0; i < directions.length; i++) {
                if (directions[i] == options.direction) {
                    continue;
                }
                List<RenderAsset> fallback = collectRenderAssets(sprite, assetsXml, visualizationXml, images, options, directions[i], renderWidth, renderHeight);
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
                int renderHeight) throws IOException {

            String size = options.small ? "32" : "64";
            Map<Integer, LayerInfo> layers = readLayers(visualizationXml, size, direction);
            Map<Integer, String> colorLayers = readColorLayers(visualizationXml, size, options.color);
            Map<Integer, Integer> animations = readAnimationFrames(visualizationXml, size, options.state);
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
            for (int layer = 0; layer < highestLayer; layer++) {
                int frame = animations.containsKey(layer) ? animations.get(layer) : 0;
                for (int i = 0; i < candidates.size(); i++) {
                    RenderAsset asset = candidates.get(i);
                    if (asset.layer == layer && (options.icon || (asset.direction == direction && asset.frame == frame))) {
                        assets.add(asset);
                    }
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

        private static Map<Integer, Integer> readAnimationFrames(XmlNode doc, String size, int state) {
            Map<Integer, Integer> result = new LinkedHashMap<>();
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
                    XmlNode frameSequence = firstChild(layer, "frameSequence");
                    XmlNode frame = firstChild(frameSequence, "frame");
                    if (id >= 0 && frame != null) {
                        result.put(id, parseInt(frame.attr("id"), 0));
                    }
                }
            }
            return result;
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

        private static void drawAsset(int[] canvas, int canvasWidth, int canvasHeight, RenderAsset asset, RenderOptions options) {
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
                drawAdd(canvas, canvasWidth, canvasHeight, source, width, height, x, y, isTransparentCanvas(options));
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

        private static void drawAdd(int[] canvas, int canvasWidth, int canvasHeight, byte[] source, int sourceWidth, int sourceHeight, int x, int y, boolean preserveDestinationAlpha) {
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
                    int outAlpha;
                    int r;
                    int g;
                    int b;
                    if (preserveDestinationAlpha) {
                        outAlpha = bgAlpha;
                        if (outAlpha == 0) {
                            continue;
                        }
                        r = clamp(red(bgPixel) + (source[sourceIndex] & 255));
                        g = clamp(green(bgPixel) + (source[sourceIndex + 1] & 255));
                        b = clamp(blue(bgPixel) + (source[sourceIndex + 2] & 255));
                    } else {
                        outAlpha = blendNormalAlpha(fgAlpha, bgAlpha);
                        r = blendAddChannel(source[sourceIndex] & 255, fgAlpha, red(bgPixel), bgAlpha, outAlpha);
                        g = blendAddChannel(source[sourceIndex + 1] & 255, fgAlpha, green(bgPixel), bgAlpha, outAlpha);
                        b = blendAddChannel(source[sourceIndex + 2] & 255, fgAlpha, blue(bgPixel), bgAlpha, outAlpha);
                    }
                    canvas[cy * canvasWidth + cx] = argb(outAlpha, r, g, b);
                }
            }
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
            if (options.background && options.backgroundRgba != null && options.backgroundRgba.length >= width * height * 4) {
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
            return !options.background && alpha(parseCanvasColor(options.canvas)) == 0;
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

    private static final class PngEncoder {
        private static byte[] encode(int width, int height, byte[] rgba) {
            int scanlineLength = width * 4 + 1;
            byte[] raw = new byte[scanlineLength * height];
            for (int y = 0; y < height; y++) {
                int rawOffset = y * scanlineLength;
                int dataOffset = y * width * 4;
                raw[rawOffset] = 0;
                System.arraycopy(rgba, dataOffset, raw, rawOffset + 1, width * 4);
            }
            byte[] compressed = zlibStore(raw);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            write(out, new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            chunk(out, "IHDR", pngHeader(width, height));
            chunk(out, "IDAT", compressed);
            chunk(out, "IEND", new byte[0]);
            return out.toByteArray();
        }

        private static byte[] pngHeader(int width, int height) {
            byte[] header = new byte[13];
            writeUint32(header, 0, width);
            writeUint32(header, 4, height);
            header[8] = 8;
            header[9] = 6;
            return header;
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

        private static void write(ByteArrayOutputStream out, byte[] bytes) {
            out.write(bytes, 0, bytes.length);
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
