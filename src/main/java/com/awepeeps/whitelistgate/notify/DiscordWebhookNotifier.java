package com.awepeeps.whitelistgate.notify;

import com.awepeeps.whitelistgate.core.ApprovalRequest;
import com.awepeeps.whitelistgate.core.SecretScrubber;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class DiscordWebhookNotifier implements RequestNotifier {
    private final WebhookClient client;
    private final Logger logger;
    private final int maxQueued;
    private final int messagesPerMinute;
    private final long intervalMillis;
    private final ScheduledExecutorService dispatcher =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "wlgate-webhook-dispatch");
                thread.setDaemon(true);
                return thread;
            });
    private final Map<String, Queued> queue = new LinkedHashMap<>();
    private final Set<String> inFlight = new HashSet<>();
    private boolean scheduled;
    private boolean closed;
    private long nextDispatchMillis;
    private long windowStartedMillis;
    private int dispatchedInWindow;

    public DiscordWebhookNotifier(WebhookClient client, Logger logger, int maxQueued,
                                  int messagesPerMinute) {
        this.client = client;
        this.logger = logger;
        this.maxQueued = maxQueued;
        this.messagesPerMinute = messagesPerMinute;
        this.intervalMillis = Math.max(1, 60_000L / messagesPerMinute);
    }

    @Override
    public synchronized void notifyRequest(ApprovalRequest request, Consumer<String> messageIdSaved) {
        enqueue(request, messageIdSaved);
    }

    @Override
    public synchronized void notifyOutcome(ApprovalRequest request, Consumer<String> messageIdSaved) {
        enqueue(request, messageIdSaved);
    }

    private void enqueue(ApprovalRequest request, Consumer<String> messageIdSaved) {
        if (closed) {
            return;
        }
        if (!queue.containsKey(request.requestId())
                && queue.size() + inFlight.size() >= maxQueued) {
            logger.warning("Whitelist notification queue is full; request remains available locally.");
            return;
        }
        queue.put(request.requestId(), new Queued(request, messageIdSaved));
        schedule(0);
    }

    @Override
    public synchronized void attachMessageId(String requestId, String messageId) {
        Queued queued = queue.get(requestId);
        if (queued != null) {
            queue.put(requestId, new Queued(
                    queued.request().withMessageId(messageId), queued.messageIdSaved()));
        }
    }

    private synchronized void schedule(long requestedDelay) {
        if (closed || scheduled || queue.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (windowStartedMillis == 0 || now - windowStartedMillis >= 60_000) {
            windowStartedMillis = now;
            dispatchedInWindow = 0;
        }
        if (dispatchedInWindow >= messagesPerMinute) {
            requestedDelay = Math.max(requestedDelay,
                    60_000 - (now - windowStartedMillis));
        }
        long delay = Math.max(requestedDelay,
                Math.max(0, nextDispatchMillis - System.currentTimeMillis()));
        scheduled = true;
        dispatcher.schedule(this::dispatch, delay, TimeUnit.MILLISECONDS);
    }

    private void dispatch() {
        Queued next = null;
        synchronized (this) {
            scheduled = false;
            if (closed) {
                return;
            }
            long now = System.currentTimeMillis();
            if (windowStartedMillis == 0 || now - windowStartedMillis >= 60_000) {
                windowStartedMillis = now;
                dispatchedInWindow = 0;
            }
            if (dispatchedInWindow >= messagesPerMinute) {
                schedule(60_000 - (now - windowStartedMillis));
                return;
            }
            Iterator<Map.Entry<String, Queued>> iterator = queue.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Queued> entry = iterator.next();
                if (!inFlight.contains(entry.getKey())) {
                    next = entry.getValue();
                    iterator.remove();
                    inFlight.add(entry.getKey());
                    break;
                }
            }
            if (next == null) {
                return;
            }
            dispatchedInWindow++;
            nextDispatchMillis = System.currentTimeMillis() + intervalMillis;
        }

        Queued selected = next;
        String requestId = selected.request().requestId();
        var sent = selected.request().discordMessageId() == null
                ? client.create(selected.request())
                : client.edit(selected.request().discordMessageId(), selected.request())
                        .thenApply(ignored -> selected.request().discordMessageId());
        sent.whenComplete((messageId, error) -> {
            if (error != null) {
                logger.warning("Discord webhook delivery failed: "
                        + SecretScrubber.safeError(error));
            } else if (messageId != null) {
                selected.messageIdSaved().accept(messageId);
                attachMessageId(requestId, messageId);
            }
            synchronized (DiscordWebhookNotifier.this) {
                inFlight.remove(requestId);
                schedule(0);
            }
        });
        synchronized (this) {
            schedule(0);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        queue.clear();
        dispatcher.shutdownNow();
    }

    private record Queued(ApprovalRequest request, Consumer<String> messageIdSaved) {}
}
