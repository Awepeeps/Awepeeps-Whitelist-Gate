package com.awepeeps.whitelistgate.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.awepeeps.whitelistgate.core.ApprovalRequest;
import com.awepeeps.whitelistgate.core.Platform;
import com.awepeeps.whitelistgate.core.RequestBook;
import com.awepeeps.whitelistgate.core.RequestState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileRequestStoreTest {
    @TempDir
    Path directory;

    @Test
    void roundTripsRequestState() throws Exception {
        Path path = directory.resolve("requests.properties");
        FileRequestStore store = new FileRequestStore(path, (backup, error) -> {
            throw new AssertionError("unexpected corruption recovery", error);
        });
        Instant now = Instant.parse("2026-05-01T12:00:00Z");
        RequestBook book = new RequestBook(List.of(), 5, Duration.ofMinutes(5),
                Duration.ofDays(1), Duration.ofDays(1));
        ApprovalRequest original = book.recordAttempt(UUID.randomUUID(),
                "Fake_Name", Platform.BEDROCK, now).request();
        original = book.transition(original.requestId(), RequestState.APPROVED,
                "FakeOperator", now.plusSeconds(10));
        original = book.setMessageId(original.requestId(), "123456789012345678");
        original = book.all().getFirst();

        store.save(List.of(original));
        List<ApprovalRequest> loaded = store.load();

        assertEquals(List.of(original), loaded);
    }

    @Test
    void backsUpCorruptStateAndStartsEmpty() throws Exception {
        Path path = directory.resolve("requests.properties");
        Files.writeString(path, "not a valid properties store");
        AtomicReference<Path> backup = new AtomicReference<>();
        FileRequestStore store = new FileRequestStore(path,
                (backupPath, error) -> backup.set(backupPath));

        assertTrue(store.load().isEmpty());
        assertTrue(backup.get() != null && Files.exists(backup.get()));
        assertFalse(Files.exists(path));
    }
}
