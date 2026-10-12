package com.awepeeps.whitelistgate.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DiscordWebhookNotifierTest {
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/webhooks/fake", exchange -> {
            String method = exchange.getRequestMethod();
            requests.add(method + " " + exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            if ("POST".equals(method)) {
                byte[] body = "{\"id\":\"111222333444555666\"}".getBytes();
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
    void enforcesTheConfiguredGlobalSendRate() throws Exception {
        DiscordWebhookNotifier notifier = new DiscordWebhookNotifier(
                client(), Logger.getLogger("test"), 50, 1);
        try {
            notifier.notifyRequest(request("AAAA1111", null), id -> {});
            notifier.notifyRequest(request("BBBB2222", null), id -> {});

            waitFor(() -> !requests.isEmpty(), Duration.ofSeconds(5));
            Thread.sleep(750);
            // A one-per-minute limit must hold the second message back for ~60s.
            assertEquals(1, requests.size());
            assertTrue(requests.get(0).startsWith("POST "));
        } finally {
            notifier.close();
        }
    }

    @Test
    void editsTheStoredMessageWhenAnOutcomeArrives() throws Exception {
        DiscordWebhookNotifier notifier = new DiscordWebhookNotifier(
                client(), Logger.getLogger("test"), 50, 60);
        try {
            notifier.notifyOutcome(request("CCCC3333", "555666777"), id -> {});
            waitFor(() -> !requests.isEmpty(), Duration.ofSeconds(5));
            assertEquals("PATCH /api/webhooks/fake/messages/555666777", requests.get(0));
        } finally {
            notifier.close();
        }
    }

    private WebhookClient client() {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/api/webhooks/fake");
        return new WebhookClient(uri, HttpClient.newHttpClient(), Duration.ofSeconds(3));
    }

    private static ApprovalRequest request(String requestId, String messageId) {
        Instant now = Instant.now();
        boolean pending = messageId == null;
        return new ApprovalRequest(requestId, UUID.randomUUID(), Platform.JAVA, "FakeName",
                now, now, 1, pending ? RequestState.PENDING : RequestState.APPROVED,
                pending ? null : now, "CONSOLE", messageId, null);
    }

    private static void waitFor(BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
    }
}
