package com.awepeeps.whitelistgate.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.awepeeps.whitelistgate.notify.WebhookSecretResolver.Resolution;
import com.awepeeps.whitelistgate.notify.WebhookSecretResolver.State;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class WebhookSecretResolverTest {
    private static final String ENV_NAME = "WLGATE_DISCORD_WEBHOOK_URL";
    private static final String VALID_URL =
            "https://discord.com/api/webhooks/123456789012345678/fake-token-for-test";

    @TempDir
    Path directory;

    private final CapturingHandler handler = new CapturingHandler();

    @Test
    void missingConfiguredFileWarnsExactlyOnceWithPathAndVariableAndNoException() {
        Path secret = directory.resolve("wlgate-webhook-url");

        Resolution resolution = WebhookSecretResolver.resolve(
                ENV_NAME, name -> null, secret.toString(), logger());

        assertNull(resolution.uri());
        assertEquals(State.FILE_MISSING, resolution.state());
        assertEquals(1, handler.records.size());
        assertEquals(Level.WARNING, handler.records.getFirst().getLevel());
        String message = handler.records.getFirst().getMessage();
        assertTrue(message.contains(secret.toString()));
        assertTrue(message.contains(ENV_NAME));
        assertFalse(message.contains("Exception"));
        assertFalse(message.contains("http"));
    }

    @Test
    void nothingConfiguredWarnsExactlyOnce() {
        Resolution resolution = WebhookSecretResolver.resolve(
                ENV_NAME, name -> null, "", logger());

        assertNull(resolution.uri());
        assertEquals(State.NOT_CONFIGURED, resolution.state());
        assertEquals(1, handler.records.size());
        assertTrue(handler.records.getFirst().getMessage().contains(ENV_NAME));
    }

    @Test
    void emptyFileWarnsDistinctly() throws Exception {
        Path secret = directory.resolve("wlgate-webhook-url");
        Files.writeString(secret, "   \n");

        Resolution resolution = WebhookSecretResolver.resolve(
                ENV_NAME, name -> null, secret.toString(), logger());

        assertNull(resolution.uri());
        assertEquals(State.FILE_EMPTY, resolution.state());
        assertEquals(1, handler.records.size());
        String message = handler.records.getFirst().getMessage();
        assertTrue(message.contains("empty"));
        assertFalse(message.contains("does not exist"));
    }

    @Test
    void unreadableFileWarnsDistinctly() throws Exception {
        // A directory at the configured path exists but cannot be read as a file.
        Path secret = Files.createDirectory(directory.resolve("wlgate-webhook-url"));

        Resolution resolution = WebhookSecretResolver.resolve(
                ENV_NAME, name -> null, secret.toString(), logger());

        assertNull(resolution.uri());
        assertEquals(State.FILE_UNREADABLE, resolution.state());
        assertEquals(1, handler.records.size());
        assertTrue(handler.records.getFirst().getMessage().contains("could not be read"));
    }

    @Test
    void invalidFileContentWarnsDistinctly() throws Exception {
        Path secret = directory.resolve("wlgate-webhook-url");
        Files.writeString(secret, "not-a-webhook-url");

        Resolution resolution = WebhookSecretResolver.resolve(
                ENV_NAME, name -> null, secret.toString(), logger());

        assertNull(resolution.uri());
        assertEquals(State.INVALID_URL, resolution.state());
        assertEquals(1, handler.records.size());
        assertTrue(handler.records.getFirst().getMessage()
                .contains("valid HTTPS Discord webhook URL"));
    }

    @Test
    void environmentValueTakesPrecedenceOverTheFile() throws Exception {
        Path missing = directory.resolve("wlgate-webhook-url");

        Resolution resolution = WebhookSecretResolver.resolve(
                ENV_NAME, name -> VALID_URL, missing.toString(), logger());

        assertTrue(resolution.configured());
        assertEquals(State.CONFIGURED, resolution.state());
        assertEquals(VALID_URL, resolution.uri().toString());
        assertTrue(handler.records.isEmpty());
    }

    @Test
    void blankEnvironmentValueFallsBackToTheFile() throws Exception {
        Path secret = directory.resolve("wlgate-webhook-url");
        Files.writeString(secret, VALID_URL + "\n");

        Resolution resolution = WebhookSecretResolver.resolve(
                ENV_NAME, name -> "   ", secret.toString(), logger());

        assertTrue(resolution.configured());
        assertEquals(State.CONFIGURED, resolution.state());
        assertTrue(handler.records.isEmpty());
    }

    @Test
    void relativeMissingPathDoesNotThrow() {
        Resolution resolution = WebhookSecretResolver.resolve(
                ENV_NAME, name -> null, "definitely-missing-wlgate-secret", logger());

        assertNull(resolution.uri());
        assertEquals(State.FILE_MISSING, resolution.state());
        assertEquals(1, handler.records.size());
    }

    @Test
    void fileCreatedLaterIsPickedUpOnReload() throws Exception {
        Path secret = directory.resolve("wlgate-webhook-url");

        Resolution before = WebhookSecretResolver.resolve(
                ENV_NAME, name -> null, secret.toString(), logger());
        assertEquals(State.FILE_MISSING, before.state());
        assertEquals(1, handler.records.size());

        Files.writeString(secret, VALID_URL);
        Resolution after = WebhookSecretResolver.resolve(
                ENV_NAME, name -> null, secret.toString(), logger());

        assertTrue(after.configured());
        assertEquals(State.CONFIGURED, after.state());
        assertEquals(1, handler.records.size(), "reload must not add a second warning");
    }

    private Logger logger() {
        Logger logger = Logger.getLogger("wlgate-resolver-test-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        return logger;
    }

    private static final class CapturingHandler extends Handler {
        private final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }
}
