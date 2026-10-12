package com.awepeeps.whitelistgate.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.awepeeps.whitelistgate.core.ApprovalRequest;
import com.awepeeps.whitelistgate.core.Platform;
import com.awepeeps.whitelistgate.core.RequestState;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebhookClientTest {
    private HttpServer server;
    private AtomicInteger calls;
    private AtomicInteger serverErrorCalls;
    private AtomicReference<String> lastMethod;
    private AtomicReference<String> lastPath;
    private AtomicReference<String> lastBody;

    @BeforeEach
    void startServer() throws Exception {
        calls = new AtomicInteger();
        serverErrorCalls = new AtomicInteger();
        lastMethod = new AtomicReference<>();
        lastPath = new AtomicReference<>();
        lastBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/webhooks/unstable", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (serverErrorCalls.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(503, -1);
            } else {
                byte[] body = "{\"id\":\"111111111111111111\"}".getBytes();
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.createContext("/api/webhooks/fake", exchange -> {
            int call = calls.incrementAndGet();
            lastMethod.set(exchange.getRequestMethod());
            lastPath.set(exchange.getRequestURI().toString());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes()));
            if (call == 1 && exchange.getRequestMethod().equals("POST")
                    && "/api/webhooks/fake".equals(exchange.getRequestURI().getPath())) {
                exchange.getResponseHeaders().add("Retry-After", "0.1");
                exchange.sendResponseHeaders(429, -1);
            } else if (exchange.getRequestMethod().equals("POST")) {
                byte[] body = "{\"id\":\"987654321012345678\"}".getBytes();
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(204, -1);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void createsEditsAndRetries429WithoutLeavingTheLocalServer() throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/api/webhooks/fake");
        WebhookClient client = new WebhookClient(uri, HttpClient.newHttpClient(),
                Duration.ofSeconds(3));
        ApprovalRequest request = new ApprovalRequest("ABCD2345",
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                Platform.BEDROCK, "@everyone_fake", Instant.now(), Instant.now(),
                2, RequestState.PENDING, null, null, null, Instant.now());

        String messageId = client.create(request).toCompletableFuture().get(5, TimeUnit.SECONDS);
        client.edit(messageId, request).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals("987654321012345678", messageId);
        assertEquals(3, calls.get());
        assertEquals("PATCH", lastMethod.get());
        assertTrue(lastPath.get().endsWith("/messages/987654321012345678"));
        assertTrue(lastBody.get().contains("\"allowed_mentions\":{\"parse\":[]}"));
        assertTrue(lastBody.get().contains("＠everyone"));
        assertFalse(lastBody.get().contains("@everyone"));
    }

    @Test
    void onlyAcceptsHttpsDiscordWebhookUrlsInProductionConfiguration() {
        assertTrue(WebhookClient.isDiscordWebhook(URI.create(
                "https://discord.com/api/webhooks/123456/fake-token")));
        assertFalse(WebhookClient.isDiscordWebhook(URI.create(
                "http://127.0.0.1/api/webhooks/fake")));
        assertFalse(WebhookClient.isDiscordWebhook(URI.create(
                "https://example.invalid/api/webhooks/123456/fake-token")));
    }

    @Test
    void retriesServerErrorsBeforeSucceeding() throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/api/webhooks/unstable");
        WebhookClient client = new WebhookClient(uri, HttpClient.newHttpClient(),
                Duration.ofSeconds(3));
        ApprovalRequest request = new ApprovalRequest("WXYZ6789",
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                Platform.JAVA, "FakeName", Instant.now(), Instant.now(),
                1, RequestState.PENDING, null, null, null, Instant.now());

        String messageId = client.create(request).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals("111111111111111111", messageId);
        assertEquals(2, serverErrorCalls.get());
    }
}
