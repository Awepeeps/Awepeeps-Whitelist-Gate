package com.awepeeps.whitelistgate.storage;

import com.awepeeps.whitelistgate.core.RequestState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public final class AuditLog {
    private static final int MAX_ENTRIES = 10_000;
    private final Path path;

    public AuditLog(Path path) {
        this.path = path;
    }

    public synchronized void append(String requestId, UUID uuid, RequestState decision,
                                    String actor, Instant timestamp) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        String safeActor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                actor.getBytes(StandardCharsets.UTF_8));
        String line = timestamp + "\t" + requestId + "\t" + uuid + "\t"
                + decision + "\t" + safeActor + System.lineSeparator();
        Files.writeString(path, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized void retainSince(Instant cutoff) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        List<String> kept = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            try {
                Instant timestamp = Instant.parse(line.split("\\t", 2)[0]);
                if (!timestamp.isBefore(cutoff)) {
                    kept.add(line);
                }
            } catch (RuntimeException ignored) {
                // Malformed rows are discarded during retention.
            }
        }
        if (kept.size() > MAX_ENTRIES) {
            kept = new ArrayList<>(kept.subList(kept.size() - MAX_ENTRIES, kept.size()));
        }
        Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(),
                path.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, kept, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
