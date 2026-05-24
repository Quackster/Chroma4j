package com.quackster.chromawebapp.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeControllerTest {
    private static final String CACHE_HIT_SPRITE = "cache_hit_sprite";

    @AfterEach
    void cleanCache() throws Exception {
        deleteRecursively(Paths.get("furni_export", CACHE_HIT_SPRITE));
    }

    @Test
    void parsesBooleansWithCSharpQueryCaseSensitivity() {
        assertTrue(HomeController.parseBoolean("1"));
        assertTrue(HomeController.parseBoolean("true"));
        assertFalse(HomeController.parseBoolean("True"));
        assertFalse(HomeController.parseBoolean("false"));
        assertFalse(HomeController.parseBoolean(null));
    }

    @Test
    void acceptsLongNumericInputBeforeIntParsingLikeCSharp() {
        assertTrue(HomeController.isNumeric("2147483648"));
        assertThrows(NumberFormatException.class, () -> HomeController.parseNumeric("2147483648", 0));
    }

    @Test
    void normalizesRequestOptionsForCacheKeyLikeCSharpController() {
        HomeController.RenderRequestOptions options = HomeController.RenderRequestOptions.from(
                "rare_dragonlamp",
                "True",
                "1",
                "101",
                "2",
                "4",
                "16",
                "15",
                "False",
                "0",
                "true",
                "ABCDEF",
                "TRUE",
                null
        );

        assertEquals("rare_dragonlamp", options.sprite());
        assertTrue(options.isSmallFurni());
        assertEquals(0, options.renderState());
        assertEquals(4, options.renderDirection());
        assertEquals(15, options.colorId());
        assertTrue(options.renderShadows());
        assertTrue(options.renderBackground());
        assertEquals("ABCDEF", options.renderCanvasColour());
        assertFalse(options.cropImage());
        assertFalse(options.renderIcon());
        assertEquals("rare_dragonlampTrue0415TrueTrueABCDEFFalseFalseFalse", options.cacheKey());
    }

    @Test
    void hashesCacheKeyWithUppercaseSha1LikeCSharpController() {
        String cacheKey = "rare_dragonlampTrue004TrueTrueABCDEFTrueFalse";

        assertEquals("87CA9B37B52BCBC044ADE7EE909E1F5C7DF72726", HomeController.hash(cacheKey));
    }

    @Test
    void returnsNullWhenSpriteIsMissingLikeCSharpController() {
        HomeController controller = new HomeController();

        assertNull(controller.index(null, null, null, null, null, null, null, null, null, null, null, null, null, null));
        assertNull(controller.index("", null, null, null, null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void returnsCachedPngResponseWithoutRenderingLikeCSharpController() throws Exception {
        HomeController.RenderRequestOptions options = HomeController.RenderRequestOptions.from(
                CACHE_HIT_SPRITE,
                "true",
                null,
                "2",
                "4",
                null,
                "1",
                null,
                "true",
                "1",
                "true",
                "336699",
                "false",
                null
        );
        byte[] pngBytes = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47};
        Path cachePath = Paths.get("furni_export", CACHE_HIT_SPRITE, "export", options.cacheHash() + ".png");
        Files.createDirectories(cachePath.getParent());
        Files.write(cachePath, pngBytes);

        ResponseEntity<byte[]> response = new HomeController().index(
                CACHE_HIT_SPRITE,
                "true",
                null,
                "2",
                "4",
                null,
                "1",
                null,
                "true",
                "1",
                "true",
                "336699",
                "false",
                null
        );

        assertNotNull(response);
        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertArrayEquals(pngBytes, response.getBody());
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path item : stream.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }
}
