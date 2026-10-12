package com.awepeeps.whitelistgate.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StoragePathsTest {
    @Test
    void resolvesRelativeDataFolderToAbsolutePath() {
        Path resolved = StoragePaths.fileInDataFolder(
                Path.of("plugins", "Awepeeps-Whitelist-Gate"), "requests.properties");

        assertTrue(resolved.isAbsolute());
        assertTrue(resolved.endsWith(Path.of(
                "plugins", "Awepeeps-Whitelist-Gate", "requests.properties")));
    }

    @Test
    void absolutisesRelativeFileWithoutParentLookup() {
        Path resolved = StoragePaths.absoluteFile(Path.of("decisions.log"));

        assertTrue(resolved.isAbsolute());
        assertFalse(resolved.getFileName() == null);
        assertEquals("decisions.log", resolved.getFileName().toString());
        assertEquals(Path.of("").toAbsolutePath().normalize(), resolved.getParent());
    }

    @Test
    void nullFolderFallsBackToWorkingDirectory() {
        Path resolved = StoragePaths.fileInDataFolder(null, "requests.properties");

        assertTrue(resolved.isAbsolute());
        assertEquals(Path.of("").toAbsolutePath().normalize().resolve("requests.properties"),
                resolved);
    }

    @Test
    void parentDirectoryIsNeverNullEvenForRoot() {
        Path root = Path.of("/").toAbsolutePath();

        Path parent = StoragePaths.parentDirectory(root);

        assertNotNull(parent);
        assertTrue(parent.isAbsolute());
    }

    @Test
    void parentDirectoryOfRelativeFileIsAbsolute() {
        Path parent = StoragePaths.parentDirectory(Path.of("requests.properties"));

        assertTrue(parent.isAbsolute());
        assertEquals(Path.of("").toAbsolutePath().normalize(), parent);
    }
}
