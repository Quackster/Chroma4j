package com.quackster.chromawebapp.controller;

import com.quackster.chroma.ChromaFurniture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Main controller for furniture rendering requests
 */
@RestController
public class HomeController {
    
    private static final Logger logger = LoggerFactory.getLogger(HomeController.class);
    
    @GetMapping("/")
    public ResponseEntity<byte[]> index(
            @RequestParam(name = "sprite", required = false) String sprite,
            @RequestParam(name = "small", required = false) String small,
            @RequestParam(name = "s", required = false) String s,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "direction", required = false) String direction,
            @RequestParam(name = "rotation", required = false) String rotation,
            @RequestParam(name = "color", required = false) String color,
            @RequestParam(name = "colour", required = false) String colour,
            @RequestParam(name = "bg", required = false) String bg,
            @RequestParam(name = "crop", required = false) String crop,
            @RequestParam(name = "shadow", required = false) String shadow,
            @RequestParam(name = "canvas", required = false) String canvas,
            @RequestParam(name = "icon", required = false) String icon,
            @RequestParam(name = "gif", required = false) String gif,
            @RequestParam(name = "apng", required = false) String apng,
            @RequestParam(name = "format", required = false) String format,
            @RequestParam(name = "loop", required = false) String loop
    ) {

        RenderRequestOptions options = RenderRequestOptions.from(
                sprite,
                small,
                s,
                state,
                direction,
                rotation,
                color,
                colour,
                bg,
                crop,
                shadow,
                canvas,
                icon,
                gif,
                apng,
                format,
                loop
        );

        if (sprite == null || sprite.isEmpty()) {
            return null;
        }

        String hashedUniqueName = options.cacheHash();

        Path exportDir = Paths.get("furni_export", sprite, "export");
        Path cachedImagePath = exportDir.resolve(hashedUniqueName + options.extension());

        try {
            Files.createDirectories(exportDir);

            if (!Files.exists(cachedImagePath)) {
                logger.info("Generating furniture image for sprite: {}", sprite);

                String swfPath = "swfs/hof_furni/" + sprite + ".swf";
                ChromaFurniture furni = new ChromaFurniture(
                        swfPath,
                        options.isSmallFurni(),
                        options.renderState(),
                        options.renderDirection(),
                        options.colorId(),
                        options.renderShadows(),
                        options.renderBackground(),
                        options.renderCanvasColour(),
                        options.cropImage(),
                        options.renderIcon()
                );

                furni.run();
                byte[] bytes;
                if (options.renderApng()) {
                    bytes = furni.createApng(options.loop());
                } else if (options.renderGif()) {
                    bytes = furni.createGif(options.loop());
                } else {
                    bytes = furni.createImage();
                }
                Files.write(cachedImagePath, bytes != null ? bytes : new byte[0]);
            }

            if (Files.exists(cachedImagePath)) {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(options.contentType());

                return ResponseEntity.ok()
                        .headers(headers)
                        .body(Files.readAllBytes(cachedImagePath));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return null;
    }
    
    static boolean parseBoolean(String value) {
        return "1".equals(value) || "true".equals(value);
    }
    
    static int parseNumeric(String value, int defaultValue) {
        if (!isNumeric(value)) {
            return defaultValue;
        }

        return Integer.parseInt(value.trim());
    }

    static boolean isNumeric(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }

        try {
            Long.parseLong(value.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String csharpBool(boolean value) {
        return value ? "True" : "False";
    }
    
    /**
     * Creates a SHA-1 hash of the input string
     */
    static String hash(String input) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            
            for (byte b : hash) {
                sb.append(String.format("%02X", b));
            }
            
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 algorithm not available", e);
        }
    }

    record RenderRequestOptions(
            String sprite,
            boolean isSmallFurni,
            int renderState,
            int renderDirection,
            int colorId,
            boolean renderShadows,
            boolean renderBackground,
            String renderCanvasColour,
            boolean cropImage,
            boolean renderIcon,
            boolean renderGif,
            boolean renderApng,
            boolean loop
    ) {
        static RenderRequestOptions from(
                String sprite,
                String small,
                String s,
                String state,
                String direction,
                String rotation,
                String color,
                String colour,
                String bg,
                String crop,
                String shadow,
                String canvas,
                String icon,
                String gif,
                String apng,
                String format,
                String loop
        ) {
            boolean isSmallFurni = parseBoolean(small) || parseBoolean(s);
            int renderState = parseNumeric(state, 0);
            int renderDirection = parseNumeric(direction, 0);
            if (rotation != null && !rotation.isEmpty()) {
                renderDirection = parseNumeric(rotation, renderDirection);
            }

            int colorId = 0;
            if (isNumeric(color)) {
                colorId = parseNumeric(color, 0);
                if (colorId >= 16) {
                    colorId = 0;
                }
            }
            if (isNumeric(colour)) {
                colorId = parseNumeric(colour, colorId);
                if (colorId >= 16) {
                    colorId = 0;
                }
            }

            boolean renderBackground = false;
            if (bg != null) {
                renderBackground = !("0".equals(bg) || "false".equals(bg));
            }
            boolean renderShadows = parseBoolean(shadow);
            boolean cropImage = true;
            if (crop != null) {
                cropImage = parseBoolean(crop);
            }
            String renderCanvasColour = "transparent";
            if (canvas != null) {
                renderCanvasColour = canvas;
            }
            boolean renderIcon = parseBoolean(icon);
            String normalizedFormat = format == null ? "" : format.trim().toLowerCase();
            boolean renderApng = parseBoolean(apng) || "apng".equals(normalizedFormat);
            boolean renderGif = !renderApng && (parseBoolean(gif) || "gif".equals(normalizedFormat));
            boolean loopAnimation = loop == null || parseBoolean(loop);

            if (renderState >= 101) {
                renderState = 0;
            }
            if (colorId >= 16) {
                colorId = 0;
            }

            return new RenderRequestOptions(
                    sprite,
                    isSmallFurni,
                    renderState,
                    renderDirection,
                    colorId,
                    renderShadows,
                    renderBackground,
                    renderCanvasColour,
                    cropImage,
                    renderIcon,
                    renderGif,
                    renderApng,
                    loopAnimation
            );
        }

        String cacheKey() {
            return sprite + csharpBool(isSmallFurni) + renderState + renderDirection +
                    colorId + csharpBool(renderShadows) + csharpBool(renderBackground) +
                    renderCanvasColour + csharpBool(cropImage) + csharpBool(renderIcon) +
                    csharpBool(renderGif) + csharpBool(renderApng) + csharpBool(loop);
        }

        String cacheHash() {
            return hash(cacheKey());
        }

        String extension() {
            if (renderApng) {
                return ".apng";
            }
            return renderGif ? ".gif" : ".png";
        }

        MediaType contentType() {
            return renderGif ? MediaType.IMAGE_GIF : MediaType.IMAGE_PNG;
        }
    }
}
