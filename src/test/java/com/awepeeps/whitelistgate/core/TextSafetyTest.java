package com.awepeeps.whitelistgate.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextSafetyTest {
    @Test
    void sanitizesMentionsMarkdownAndControlCharacters() {
        String sanitized = TextSafety.discord("@everyone **hello**\n\u0000");

        assertFalse(sanitized.contains("@everyone"));
        assertTrue(sanitized.contains("＠everyone"));
        assertFalse(sanitized.contains("\u0000"));
        assertTrue(sanitized.contains("\\*\\*hello\\*\\*"));
    }

    @Test
    void scrubsUrlsFromErrors() {
        String secret = "https://discord.com/api/webhooks/123456/fake-token";
        String result = SecretScrubber.scrub("request to " + secret + " failed");

        assertFalse(result.contains(secret));
        assertTrue(result.contains("[redacted URL]"));
    }

    @Test
    void safeErrorUnwrapsAndScrubs() {
        Throwable failure = new java.util.concurrent.CompletionException(
                new IllegalStateException(
                        "connect to https://discord.com/api/webhooks/123456/fake-token failed"));
        String result = SecretScrubber.safeError(failure);

        assertTrue(result.startsWith("IllegalStateException"));
        assertFalse(result.contains("discord.com"));
    }
}
