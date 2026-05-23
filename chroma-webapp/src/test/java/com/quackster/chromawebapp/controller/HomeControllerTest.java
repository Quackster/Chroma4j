package com.quackster.chromawebapp.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeControllerTest {

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
                "TRUE"
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
        assertEquals("rare_dragonlampTrue0415TrueTrueABCDEFFalseFalse", options.cacheKey());
    }

    @Test
    void hashesCacheKeyWithUppercaseSha1LikeCSharpController() {
        String cacheKey = "rare_dragonlampTrue004TrueTrueABCDEFTrueFalse";

        assertEquals("87CA9B37B52BCBC044ADE7EE909E1F5C7DF72726", HomeController.hash(cacheKey));
    }
}
