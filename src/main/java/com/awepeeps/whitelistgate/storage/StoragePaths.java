package com.awepeeps.whitelistgate.storage;

import java.nio.file.Path;

/**
 * Resolves plugin filesystem paths without ever assuming that the data folder
 * is absolute or that a path has a non-null {@link Path#getParent()}.
 *
 * <p>Paper hands {@code JavaPlugin#getDataFolder()} a path that is relative to
 * the server's working directory (for example
 * {@code plugins/Awepeeps-Whitelist-Gate}). Any code that walks upwards with
 * {@code getParent()} therefore dereferences {@code null} and crashes enable.
 * Everything the plugin touches is absolutised against the process working
 * directory here, once, up front.</p>
 */
public final class StoragePaths {
    private StoragePaths() {}

    /** Absolute, normalised data directory for a possibly-relative data folder. */
    public static Path dataDirectory(Path dataFolder) {
        Path base = dataFolder == null ? Path.of("") : dataFolder;
        return base.toAbsolutePath().normalize();
    }

    /** Absolute, normalised form of a possibly-relative file path. */
    public static Path absoluteFile(Path file) {
        return dataDirectory(file);
    }

    /** Absolute path of {@code fileName} inside the (possibly relative) data folder. */
    public static Path fileInDataFolder(Path dataFolder, String fileName) {
        return dataDirectory(dataFolder).resolve(fileName);
    }

    /**
     * Parent directory of {@code file}, never {@code null}.
     *
     * <p>{@link Path#getParent()} returns {@code null} for a filesystem root, so
     * callers that need a directory to create or to hold a temp file must not
     * assume it exists.</p>
     */
    public static Path parentDirectory(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            return parent;
        }
        Path root = absolute.getRoot();
        return root != null ? root : Path.of("").toAbsolutePath().normalize();
    }
}
