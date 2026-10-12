package com.awepeeps.whitelistgate.core;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class RequestBook {
    public enum Notice {
        NONE,
        NEW,
        EDIT
    }

    public record AttemptResult(ApprovalRequest request, Notice notice, boolean accepted) {}

    private static final char[] REQUEST_ID_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private final Map<UUID, ApprovalRequest> byUuid = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private int maxPending;
    private Duration notificationCooldown;
    private Duration deniedCooldown;
    private Duration pendingTtl;
    private long droppedRequests;

    public RequestBook(
            List<ApprovalRequest> initial,
            int maxPending,
            Duration notificationCooldown,
            Duration deniedCooldown,
            Duration pendingTtl) {
        configure(maxPending, notificationCooldown, deniedCooldown, pendingTtl);
        for (ApprovalRequest request : initial) {
            byUuid.put(request.uuid(), request);
        }
        pruneTerminal();
    }

    public synchronized void configure(
            int maxPending,
            Duration notificationCooldown,
            Duration deniedCooldown,
            Duration pendingTtl) {
        if (maxPending < 1 || notificationCooldown.isNegative()
                || deniedCooldown.isNegative() || pendingTtl.isNegative()
                || pendingTtl.isZero()) {
            throw new IllegalArgumentException("invalid request policy");
        }
        this.maxPending = maxPending;
        this.notificationCooldown = notificationCooldown;
        this.deniedCooldown = deniedCooldown;
        this.pendingTtl = pendingTtl;
        pruneTerminal();
    }

    public synchronized AttemptResult recordAttempt(
            UUID uuid, String name, Platform platform, Instant now) {
        ApprovalRequest current = byUuid.get(uuid);
        if (current != null && current.state() == RequestState.PENDING) {
            ApprovalRequest updated = current.attempted(name, now);
            Notice notice = current.lastNotified() == null
                    || !now.isBefore(current.lastNotified().plus(notificationCooldown))
                    ? Notice.EDIT : Notice.NONE;
            if (notice == Notice.EDIT) {
                updated = updated.notified(now);
            }
            byUuid.put(uuid, updated);
            return new AttemptResult(updated, notice, true);
        }
        if (current != null && !canReopen(current, now)) {
            return new AttemptResult(current, Notice.NONE, false);
        }
        if (pendingCount() >= maxPending) {
            droppedRequests++;
            return new AttemptResult(null, Notice.NONE, false);
        }
        if (current == null) {
            makeRoomForNewRequest();
            if (byUuid.size() >= maxPending * 4) {
                droppedRequests++;
                return new AttemptResult(null, Notice.NONE, false);
            }
        }

        String requestId = newRequestId();
        ApprovalRequest request = new ApprovalRequest(requestId, uuid, platform, name,
                now, now, 1, RequestState.PENDING, null, null, null, now);
        byUuid.put(uuid, request);
        return new AttemptResult(request, Notice.NEW, true);
    }

    private boolean canReopen(ApprovalRequest request, Instant now) {
        if (request.state() == RequestState.APPROVED) {
            return false;
        }
        Instant since = request.stateChangedAt() == null
                ? request.lastSeen() : request.stateChangedAt();
        return !now.isBefore(since.plus(deniedCooldown));
    }

    private void pruneTerminal() {
        int maximumRecords = maxPending * 4;
        while (byUuid.size() > maximumRecords) {
            if (!removeOldestTerminal()) {
                return;
            }
        }
    }

    private void makeRoomForNewRequest() {
        while (byUuid.size() >= maxPending * 4 && removeOldestTerminal()) {
        }
    }

    private boolean removeOldestTerminal() {
        ApprovalRequest oldest = byUuid.values().stream()
                .filter(request -> request.state() != RequestState.PENDING)
                .min(Comparator.comparing(request -> request.stateChangedAt() == null
                        ? request.lastSeen() : request.stateChangedAt()))
                .orElse(null);
        if (oldest == null) {
            return false;
        }
        byUuid.remove(oldest.uuid());
        return true;
    }

    private String newRequestId() {
        String result;
        do {
            StringBuilder value = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                value.append(REQUEST_ID_CHARS[random.nextInt(REQUEST_ID_CHARS.length)]);
            }
            result = value.toString();
        } while (findById(result).isPresent());
        return result;
    }

    public synchronized ApprovalRequest transition(
            String requestId, RequestState next, String actor, Instant now) {
        ApprovalRequest current = findById(requestId).orElse(null);
        if (current == null || current.state() != RequestState.PENDING) {
            return null;
        }
        ApprovalRequest updated = current.transitioned(next, actor, now);
        byUuid.put(updated.uuid(), updated);
        return updated;
    }

    public synchronized List<ApprovalRequest> expire(Instant now) {
        List<ApprovalRequest> expired = new ArrayList<>();
        for (ApprovalRequest current : List.copyOf(byUuid.values())) {
            if (current.state() == RequestState.PENDING
                    && !now.isBefore(current.lastSeen().plus(pendingTtl))) {
                ApprovalRequest updated = current.transitioned(RequestState.EXPIRED, "SYSTEM", now);
                byUuid.put(updated.uuid(), updated);
                expired.add(updated);
            }
        }
        return expired;
    }

    public synchronized ApprovalRequest setMessageId(String requestId, String messageId) {
        ApprovalRequest current = findById(requestId).orElse(null);
        if (current == null) {
            return null;
        }
        ApprovalRequest updated = current.withMessageId(messageId);
        byUuid.put(updated.uuid(), updated);
        return updated;
    }

    public synchronized ApprovalRequest remove(String selector) {
        ApprovalRequest request = find(selector).orElse(null);
        return request == null ? null : byUuid.remove(request.uuid());
    }

    public synchronized Optional<ApprovalRequest> find(String selector) {
        Optional<ApprovalRequest> byId = findById(selector);
        if (byId.isPresent()) {
            return byId;
        }
        List<ApprovalRequest> matches = byUuid.values().stream()
                .filter(request -> request.name().equalsIgnoreCase(selector))
                .filter(request -> request.state() == RequestState.PENDING)
                .toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    public synchronized Optional<ApprovalRequest> findById(String requestId) {
        if (requestId == null) {
            return Optional.empty();
        }
        return byUuid.values().stream()
                .filter(request -> request.requestId().equalsIgnoreCase(requestId))
                .findFirst();
    }

    public synchronized List<ApprovalRequest> pending() {
        return byUuid.values().stream()
                .filter(request -> request.state() == RequestState.PENDING)
                .sorted(Comparator.comparing(ApprovalRequest::firstSeen))
                .toList();
    }

    public synchronized List<ApprovalRequest> all() {
        return List.copyOf(byUuid.values());
    }

    public synchronized int pendingCount() {
        return (int) byUuid.values().stream()
                .filter(request -> request.state() == RequestState.PENDING)
                .count();
    }

    public synchronized long droppedRequests() {
        return droppedRequests;
    }
}
