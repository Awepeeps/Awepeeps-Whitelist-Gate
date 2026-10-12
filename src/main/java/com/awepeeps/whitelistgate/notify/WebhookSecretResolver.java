package com.awepeeps.whitelistgate.notify;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Resolves the Discord webhook from the configured environment variable or
 * secret file, and emits exactly one clear warning per resolution when it is not
 * usable.
 *
 * <p>A missing secret file is the normal "not configured yet" state: it means
 * the owner has not supplied the URL, not that something is broken. It must
 * never throw, and it must produce a single operator-facing line that names the
 * file path and the environment variable but never a URL or an exception class.
 * Files that exist but are unreadable, empty, or not a Discord webhook URL get a
 * distinct, clearly worded warning.</p>
 */
public final class WebhookSecretResolver {
    public enum State {
        CONFIGURED,
        NOT_CONFIGURED,
        FILE_MISSING,
        FILE_UNREADABLE,
        FILE_EMPTY,
        INVALID_URL
    }

    public record Resolution(URI uri, State state) {
        public boolean configured() {
            return uri != null;
        }
    }

    private static final String FALLBACK_ENV = "the configured environment variable";

    private WebhookSecretResolver() {}

    /**
     * @param environmentName the configured variable name (may be null/blank)
     * @param environment     environment lookup, normally {@code System::getenv}
     * @param filePath        the configured {@code secrets.webhook-file} value
     * @param logger          receives at most one warning per call
     */
    public static Resolution resolve(String environmentName,
                                     Function<String, String> environment,
                                     String filePath,
                                     Logger logger) {
        String envName = environmentName == null ? "" : environmentName.trim();
        String envLabel = envName.isEmpty() ? FALLBACK_ENV : envName;
        Function<String, String> env = environment == null ? name -> null : environment;

        String fromEnvironment = envName.isEmpty() ? null : env.apply(envName);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return interpret(fromEnvironment.trim(), logger,
                    "The webhook value from environment variable " + envLabel
                            + " is not a valid HTTPS Discord webhook URL; notifications disabled.");
        }

        String path = filePath == null ? "" : filePath.trim();
        if (path.isEmpty()) {
            logger.warning("No Discord webhook configured (environment variable " + envLabel
                    + " is unset and secrets.webhook-file is empty); requests will be stored but "
                    + "not sent.");
            return new Resolution(null, State.NOT_CONFIGURED);
        }

        Path secretPath = Path.of(path).toAbsolutePath().normalize();
        if (!Files.exists(secretPath)) {
            logger.warning("Discord webhook is not configured yet: secret file " + path
                    + " does not exist. Create that file containing only the webhook URL, or set "
                    + "the environment variable " + envLabel + "; requests will be stored but not "
                    + "sent.");
            return new Resolution(null, State.FILE_MISSING);
        }
        String content;
        try {
            content = Files.readString(secretPath).trim();
        } catch (IOException | RuntimeException failure) {
            logger.warning("Discord webhook secret file " + path + " exists but could not be read; "
                    + "requests will be stored but not sent. Check the file's permissions and "
                    + "contents, or set the environment variable " + envLabel + ".");
            return new Resolution(null, State.FILE_UNREADABLE);
        }
        if (content.isEmpty()) {
            logger.warning("Discord webhook secret file " + path + " exists but is empty; requests "
                    + "will be stored but not sent. Write the webhook URL into that file, or set the "
                    + "environment variable " + envLabel + ".");
            return new Resolution(null, State.FILE_EMPTY);
        }
        return interpret(content, logger,
                "Discord webhook secret file " + path + " does not contain a valid HTTPS Discord "
                        + "webhook URL; notifications disabled.");
    }

    private static Resolution interpret(String candidate, Logger logger, String invalidMessage) {
        URI uri;
        try {
            uri = URI.create(candidate);
        } catch (RuntimeException failure) {
            logger.warning(invalidMessage);
            return new Resolution(null, State.INVALID_URL);
        }
        if (!WebhookClient.isDiscordWebhook(uri)) {
            logger.warning(invalidMessage);
            return new Resolution(null, State.INVALID_URL);
        }
        return new Resolution(uri, State.CONFIGURED);
    }
}
