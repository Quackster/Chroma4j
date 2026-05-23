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
            @RequestParam(name = "small", required = false, defaultValue = "false") String small,
            @RequestParam(name = "s", required = false, defaultValue = "false") String s,
            @RequestParam(name = "state", required = false, defaultValue = "0") String state,
            @RequestParam(name = "direction", required = false) String direction,
            @RequestParam(name = "rotation", required = false) String rotation,
            @RequestParam(name = "color", required = false) String color,
            @RequestParam(name = "colour", required = false) String colour,
            @RequestParam(name = "bg", required = false, defaultValue = "false") String bg,
            @RequestParam(name = "crop", required = false, defaultValue = "true") String crop,
            @RequestParam(name = "shadow", required = false, defaultValue = "false") String shadow,
            @RequestParam(name = "canvas", required = false, defaultValue = "transparent") String canvas,
            @RequestParam(name = "icon", required = false, defaultValue = "false") String icon,
            @RequestParam(name = "gif", required = false, defaultValue = "false") String gif
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
        boolean renderBackground = !("0".equals(bg) || "false".equals(bg));
        boolean renderShadows = parseBoolean(shadow);
        boolean cropImage = parseBoolean(crop);
        String renderCanvasColour = canvas;
        boolean renderIcon = parseBoolean(icon);

        if (renderState >= 101) {
            renderState = 0;
        }
        if (colorId >= 16) {
            colorId = 0;
        }

        if (sprite == null || sprite.isEmpty()) {
            return null;
        }

        String fileNameUnique = sprite + csharpBool(isSmallFurni) + renderState + renderDirection +
                              colorId + csharpBool(renderShadows) + csharpBool(renderBackground) +
                              renderCanvasColour + csharpBool(cropImage) + csharpBool(renderIcon);
        String hashedUniqueName = hash(fileNameUnique);

        Path exportDir = Paths.get("furni_export", sprite, "export");
        Path cachedImagePath = exportDir.resolve(hashedUniqueName + ".png");

        try {
            Files.createDirectories(exportDir);

            if (!Files.exists(cachedImagePath)) {
                logger.info("Generating furniture image for sprite: {}", sprite);

                String swfPath = "swfs/hof_furni/" + sprite + ".swf";
                ChromaFurniture furni = new ChromaFurniture(
                        swfPath,
                        isSmallFurni,
                        renderState,
                        renderDirection,
                        colorId,
                        renderShadows,
                        renderBackground,
                        renderCanvasColour,
                        cropImage,
                        renderIcon
                );

                furni.run();
                byte[] bytes = furni.createImage();
                Files.write(cachedImagePath, bytes != null ? bytes : new byte[0]);
            }

            if (Files.exists(cachedImagePath)) {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.IMAGE_PNG);

                return ResponseEntity.ok()
                        .headers(headers)
                        .body(Files.readAllBytes(cachedImagePath));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return null;
    }
    
    /**
     * Parses a string parameter as a boolean
     */
    private boolean parseBoolean(String value) {
        return "1".equals(value) || "true".equals(value);
    }
    
    /**
     * Parses a numeric string parameter
     */
    private int parseNumeric(String value, int defaultValue) {
        if (!isNumeric(value)) {
            return defaultValue;
        }

        return Integer.parseInt(value.trim());
    }

    private boolean isNumeric(String value) {
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
    private static String hash(String input) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(input.getBytes());
            StringBuilder sb = new StringBuilder(hash.length * 2);
            
            for (byte b : hash) {
                sb.append(String.format("%02X", b));
            }
            
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 algorithm not available", e);
        }
    }
}
