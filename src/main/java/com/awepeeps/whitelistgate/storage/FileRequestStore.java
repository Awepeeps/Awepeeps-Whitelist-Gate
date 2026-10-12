package com.awepeeps.whitelistgate.storage;

import com.awepeeps.whitelistgate.core.ApprovalRequest;
import com.awepeeps.whitelistgate.core.Platform;
import com.awepeeps.whitelistgate.core.RequestState;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

public final class FileRequestStore implements RequestStore {
    private static final int VERSION = 1;
    private final Path path;
    private final CorruptionHandler corruptionHandler;

    @FunctionalInterface
    public interface CorruptionHandler {
        void recovered(Path backupPath, IOException cause);
    }

    public FileRequestStore(Path path, CorruptionHandler corruptionHandler) {
        // Absolutise once so every later sibling/parent lookup is defined even
        // when the plugin's data folder is relative.
        this.path = StoragePaths.absoluteFile(path);
        this.corruptionHandler = corruptionHandler;
    }

    @Override
    public List<ApprovalRequest> load() throws IOException {
        if (!Files.exists(path)) {
            return List.of();
        }
        try (var input = Files.newInputStream(path)) {
            if (Files.size(path) > 5_000_000) {
                throw new IOException("request store exceeds size limit");
            }
            Properties properties = new Properties();
            properties.load(input);
            if (!Integer.toString(VERSION).equals(properties.getProperty("format"))) {
                throw new IOException("unsupported request store format");
            }
            List<ApprovalRequest> requests = new ArrayList<>();
            for (String key : properties.stringPropertyNames()) {
                if (key.startsWith("request.")) {
                    if (requests.size() >= 4000) {
                        throw new IOException("request store exceeds record limit");
                    }
                    requests.add(decode(properties.getProperty(key)));
                }
            }
            return List.copyOf(requests);
        } catch (Exception failure) {
            IOException cause = failure instanceof IOException io
                    ? io : new IOException("invalid request store data", failure);
            Path backup = path.resolveSibling(path.getFileName()
                    + ".corrupt-" + System.currentTimeMillis());
            try {
                Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException backupFailure) {
                cause.addSuppressed(backupFailure);
                backup = null;
            }
            corruptionHandler.recovered(backup, cause);
            return List.of();
        }
    }

    @Override
    public synchronized void save(List<ApprovalRequest> requests) throws IOException {
        Path parent = StoragePaths.parentDirectory(path);
        Files.createDirectories(parent);
        Properties properties = new Properties();
        properties.setProperty("format", Integer.toString(VERSION));
        for (ApprovalRequest request : requests) {
            properties.setProperty("request." + request.uuid(), encode(request));
        }
        Path temporary = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(output, "Awepeeps Whitelist Gate request state");
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String encode(ApprovalRequest request) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(VERSION);
            output.writeUTF(request.requestId());
            output.writeUTF(request.uuid().toString());
            output.writeUTF(request.platform().name());
            output.writeUTF(request.name());
            output.writeLong(request.firstSeen().toEpochMilli());
            output.writeLong(request.lastSeen().toEpochMilli());
            output.writeInt(request.attempts());
            output.writeUTF(request.state().name());
            writeNullableInstant(output, request.stateChangedAt());
            writeNullable(output, request.actor());
            writeNullable(output, request.discordMessageId());
            writeNullableInstant(output, request.lastNotified());
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    }

    private static ApprovalRequest decode(String encoded) throws IOException {
        byte[] bytes = Base64.getUrlDecoder().decode(encoded);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != VERSION) {
                throw new IOException("unsupported request record format");
            }
            String requestId = input.readUTF();
            UUID uuid = UUID.fromString(input.readUTF());
            Platform platform = Platform.valueOf(input.readUTF());
            String name = input.readUTF();
            Instant firstSeen = Instant.ofEpochMilli(input.readLong());
            Instant lastSeen = Instant.ofEpochMilli(input.readLong());
            int attempts = input.readInt();
            RequestState state = RequestState.valueOf(input.readUTF());
            Instant stateChangedAt = readNullableInstant(input);
            String actor = readNullable(input);
            String messageId = readNullable(input);
            Instant lastNotified = readNullableInstant(input);
            if (input.available() != 0) {
                throw new IOException("unexpected request record data");
            }
            return new ApprovalRequest(requestId, uuid, platform, name, firstSeen,
                    lastSeen, attempts, state, stateChangedAt, actor, messageId, lastNotified);
        } catch (IllegalArgumentException failure) {
            throw new IOException("invalid request record", failure);
        }
    }

    private static void writeNullableInstant(DataOutputStream output, Instant value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) {
            output.writeLong(value.toEpochMilli());
        }
    }

    private static Instant readNullableInstant(DataInputStream input) throws IOException {
        return input.readBoolean() ? Instant.ofEpochMilli(input.readLong()) : null;
    }

    private static void writeNullable(DataOutputStream output, String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) {
            output.writeUTF(value);
        }
    }

    private static String readNullable(DataInputStream input) throws IOException {
        return input.readBoolean() ? input.readUTF() : null;
    }
}
