package com.quackster.chroma.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FileUtilTest {

    @TempDir
    Path tempDir;

    @Test
    void solveFileIgnoresDirectoriesLikeCSharpDirectoryGetFiles() throws Exception {
        Files.createDirectory(tempDir.resolve("asset_target"));

        assertNull(FileUtil.solveFile(tempDir.toString(), "target", false));
    }

    @Test
    void solveFilePreservesRelativeDirectoryFormLikeCSharpDirectoryGetFiles() throws Exception {
        Path relativeDir = Paths.get("build", "file-util-relative-test");
        Files.createDirectories(relativeDir);
        Files.writeString(relativeDir.resolve("asset_target.png"), "x");

        assertEquals(relativeDir.resolve("asset_target.png").toString(), FileUtil.solveFile(relativeDir.toString(), "target", false));
    }

    @Test
    void solveXmlFileIgnoresMatchingDirectoriesAndParsesMatchingFiles() throws Exception {
        Files.createDirectory(tempDir.resolve("visualization"));
        Path xml = tempDir.resolve("chair_visualization.xml");
        Files.writeString(xml, "<root><visualization/></root>");

        Document document = FileUtil.solveXmlFile(tempDir.toString(), "visualization");

        assertNotNull(document);
        assertEquals("root", document.getDocumentElement().getNodeName());
    }
}
