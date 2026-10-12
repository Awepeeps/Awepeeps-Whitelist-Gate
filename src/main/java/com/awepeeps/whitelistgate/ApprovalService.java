package com.awepeeps.whitelistgate;

import com.awepeeps.whitelistgate.core.ApprovalRequest;
import com.awepeeps.whitelistgate.core.Platform;
import com.awepeeps.whitelistgate.core.RequestBook;
import com.awepeeps.whitelistgate.core.RequestDecision;
import com.awepeeps.whitelistgate.core.RequestState;
import com.awepeeps.whitelistgate.core.SecretScrubber;
import com.awepeeps.whitelistgate.notify.NoopRequestNotifier;
import com.awepeeps.whitelistgate.notify.RequestNotifier;
import com.awepeeps.whitelistgate.storage.AuditLog;
import com.awepeeps.whitelistgate.storage.RequestStore;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

public final class ApprovalService implements AutoCloseable {
    public record DecisionResult(boolean changed, String message) {}

    private final WhitelistGatePlugin plugin;
    private final RequestBook requests;
    private final RequestStore store;
    private final AuditLog auditLog;
    private final Logger logger;
    private final ThreadPoolExecutor ioExecutor = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64),
            task -> {
                Thread thread = new Thread(task, "wlgate-storage");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean persistenceQueued = new AtomicBoolean();
    private final AtomicLong revision = new AtomicLong();
    private final AtomicLong persistedRevision = new AtomicLong();
    private final AtomicBoolean saturationLogged = new AtomicBoolean();
    private volatile RequestNotifier notifier = new NoopRequestNotifier();

    public ApprovalService(WhitelistGatePlugin plugin, RequestBook requests,
                           RequestStore store, AuditLog auditLog) {
        this.plugin = plugin;
        this.requests = requests;
        this.store = store;
        this.auditLog = auditLog;
        this.logger = plugin.getLogger();
        submitIo(() -> {
            try {
                auditLog.retainSince(Instant.now().minus(plugin.auditRetention()));
            } catch (IOException failure) {
                logger.warning("Decision audit retention failed: "
                        + SecretScrubber.safeError(failure));
            }
        });
    }

    public void setNotifier(RequestNotifier notifier) {
        this.notifier = notifier;
    }

    public RequestBook.AttemptResult recordAttempt(UUID uuid, String name, Platform platform) {
        RequestBook.AttemptResult result = requests.recordAttempt(uuid, name, platform, Instant.now());
        if (!result.accepted()) {
            if (result.request() == null && saturationLogged.compareAndSet(false, true)) {
                logger.warning("Pending whitelist request capacity reached; further new identities are dropped.");
            }
            return result;
        }
        markDirty();
        if (result.notice() != RequestBook.Notice.NONE) {
            notifier.notifyRequest(result.request(), messageId ->
                    saveMessageId(result.request().requestId(), messageId));
        }
        return result;
    }

    private void saveMessageId(String requestId, String messageId) {
        ApprovalRequest updated = requests.setMessageId(requestId, messageId);
        if (updated != null) {
            notifier.attachMessageId(requestId, messageId);
            markDirty();
        }
    }

    public void messageIdSaved(String requestId, String messageId) {
        saveMessageId(requestId, messageId);
    }

    public void persistCurrentState() {
        markDirty();
    }

    public void decide(String selector, RequestDecision decision, String actor,
                       Consumer<DecisionResult> completion) {
        Runnable onMain = () -> {
            ApprovalRequest current = requests.find(selector).orElse(null);
            if (current == null || current.state() != RequestState.PENDING) {
                completion.accept(new DecisionResult(false, "No matching pending request."));
                return;
            }

            RequestState next = decision == RequestDecision.APPROVED
                    ? RequestState.APPROVED : RequestState.DENIED;
            if (decision == RequestDecision.APPROVED) {
                try {
                    OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(current.uuid());
                    offlinePlayer.setWhitelisted(true);
                    if (!offlinePlayer.isWhitelisted()) {
                        completion.accept(new DecisionResult(false,
                                "Paper did not confirm the whitelist update; request remains pending."));
                        return;
                    }
                } catch (RuntimeException failure) {
                    logger.warning("Whitelist update failed: " + SecretScrubber.safeError(failure));
                    completion.accept(new DecisionResult(false,
                            "Paper could not update the whitelist; request remains pending."));
                    return;
                }
            }

            ApprovalRequest updated = requests.transition(current.requestId(), next, actor, Instant.now());
            if (updated == null) {
                completion.accept(new DecisionResult(false, "Request is no longer pending."));
                return;
            }
            markDirty();
            appendAudit(updated);
            notifier.notifyOutcome(updated, messageId ->
                    saveMessageId(updated.requestId(), messageId));
            completion.accept(new DecisionResult(true,
                    decision == RequestDecision.APPROVED
                            ? "Approved and added to Paper's whitelist."
                            : "Request denied."));
        };
        if (Bukkit.isPrimaryThread()) {
            onMain.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, onMain);
        }
    }

    public ApprovalRequest forget(String selector) {
        ApprovalRequest removed = requests.remove(selector);
        if (removed != null) {
            markDirty();
        }
        return removed;
    }

    public void expireRequests() {
        List<ApprovalRequest> expired = requests.expire(Instant.now());
        if (expired.isEmpty()) {
            return;
        }
        markDirty();
        for (ApprovalRequest request : expired) {
            appendAudit(request);
            notifier.notifyOutcome(request,
                    messageId -> saveMessageId(request.requestId(), messageId));
        }
    }

    private void appendAudit(ApprovalRequest request) {
        submitIo(() -> {
            try {
                auditLog.append(request.requestId(), request.uuid(), request.state(),
                        request.actor() == null ? "SYSTEM" : request.actor(),
                        request.stateChangedAt());
                auditLog.retainSince(Instant.now().minus(plugin.auditRetention()));
            } catch (IOException failure) {
                logger.warning("Decision audit write failed: " + SecretScrubber.safeError(failure));
            }
        });
    }

    public RequestBook requests() {
        return requests;
    }

    private void markDirty() {
        revision.incrementAndGet();
        queuePersistence();
    }

    private void queuePersistence() {
        if (!persistenceQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            ioExecutor.execute(() -> {
            try {
                while (true) {
                    long targetRevision = revision.get();
                    store.save(requests.all());
                    persistedRevision.set(targetRevision);
                    if (revision.get() == targetRevision) {
                        break;
                    }
                }
            } catch (IOException failure) {
                logger.warning("Request state could not be saved: "
                        + SecretScrubber.safeError(failure));
            } finally {
                persistenceQueued.set(false);
                if (persistedRevision.get() != revision.get()) {
                    queuePersistence();
                }
            }
            });
        } catch (RejectedExecutionException failure) {
            persistenceQueued.set(false);
            if (saturationLogged.compareAndSet(false, true)) {
                logger.warning("Request state write could not be queued; a later request will retry persistence.");
            }
        }
    }

    private void submitIo(Runnable task) {
        try {
            ioExecutor.execute(task);
        } catch (RejectedExecutionException failure) {
            logger.warning("Background storage task could not be queued: "
                    + SecretScrubber.safeError(failure));
        }
    }

    @Override
    public void close() {
        notifier.close();
        queuePersistence();
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            ioExecutor.shutdownNow();
        }
    }
}
