package com.awepeeps.whitelistgate.core;

import java.util.regex.Pattern;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public final class SecretScrubber {
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s\\]\\[)>,;]+");

    private SecretScrubber() {}

    public static String scrub(String message) {
        return URL.matcher(message == null ? "" : message).replaceAll("[redacted URL]");
    }

    public static String safeError(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) {
            error = error.getCause();
        }
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + scrub(message));
    }
}
