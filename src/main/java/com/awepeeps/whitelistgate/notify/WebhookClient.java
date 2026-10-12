package com.awepeeps.whitelistgate.notify;

import com.awepeeps.whitelistgate.core.ApprovalRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WebhookClient {
    private static final Pattern MESSAGE_ID = Pattern.compile("\"id\"\\s*:\\s*\"(\\d+)\"");
    private final URI webhook;
    private final HttpClient httpClient;
    private final Duration timeout;

    public WebhookClient(URI webhook, HttpClient httpClient, Duration timeout) {
        this.webhook = webhook;
        this.httpClient = httpClient;
        this.timeout = timeout;
    }

    public static boolean isDiscordWebhook(URI uri) {
        String host = uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme())
                && ("discord.com".equalsIgnoreCase(host)
                || "discordapp.com".equalsIgnoreCase(host))
                && uri.getPath() != null
                && uri.getPath().matches("/api/webhooks/\\d+/[^/]+/?");
    }

    public CompletionStage<String> create(ApprovalRequest request) {
        return send("POST", appendQuery(webhook, "wait=true"), request, 0)
                .thenApply(response -> {
                    Matcher matcher = MESSAGE_ID.matcher(response.body());
                    if (!matcher.find()) {
                        throw new IllegalStateException("Discord webhook response omitted message id");
                    }
                    return matcher.group(1);
                });
    }

    public CompletionStage<Void> edit(String messageId, ApprovalRequest request) {
        return send("PATCH", messageUri(webhook, messageId), request, 0)
                .thenApply(ignored -> null);
    }

    private CompletableFuture<HttpResponse<String>> send(
            String method, URI uri, ApprovalRequest request, int retry) {
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .method(method, HttpRequest.BodyPublishers.ofString(payload(request)))
                .build();
        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    int code = response.statusCode();
                    if (code >= 200 && code < 300) {
                        return CompletableFuture.completedFuture(response);
                    }
                    if (retry >= 3 || !(code == 429 || code >= 500 || code == 408)) {
                        return CompletableFuture.failedFuture(
                                new WebhookException("Discord webhook request failed with HTTP " + code));
                    }
                    long delayMillis = retryDelay(response, retry);
                    return CompletableFuture.supplyAsync(
                                    () -> null,
                                    CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS))
                            .thenCompose(ignored -> send(method, uri, request, retry + 1));
                });
    }

    private static long retryDelay(HttpResponse<?> response, int retry) {
        if (response.statusCode() == 429) {
            try {
                double seconds = Double.parseDouble(response.headers()
                        .firstValue("Retry-After").orElse("1"));
                return Math.max(100, Math.min(30_000, (long) (seconds * 1000)));
            } catch (NumberFormatException ignored) {
                return 1_000;
            }
        }
        return Math.min(4_000, 500L << retry);
    }

    private static String payload(ApprovalRequest request) {
        return "{\"content\":\"" + jsonEscape(RequestFormatter.format(request))
                + "\",\"allowed_mentions\":{\"parse\":[]}}";
    }

    private static String jsonEscape(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static URI appendQuery(URI base, String query) {
        return URI.create(base.toString() + (base.getQuery() == null ? "?" : "&") + query);
    }

    private static URI messageUri(URI base, String messageId) {
        String value = base.toString();
        int query = value.indexOf('?');
        String suffix = query < 0 ? "" : value.substring(query);
        String path = query < 0 ? value : value.substring(0, query);
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return URI.create(path + "/messages/" + messageId + suffix);
    }

    public static final class WebhookException extends RuntimeException {
        public WebhookException(String message) {
            super(message);
        }
    }
}
