package com.awepeeps.whitelistgate.core;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ApprovalRequest(
        String requestId,
        UUID uuid,
        Platform platform,
        String name,
        Instant firstSeen,
        Instant lastSeen,
        int attempts,
        RequestState state,
        Instant stateChangedAt,
        String actor,
        String discordMessageId,
        Instant lastNotified) {

    public ApprovalRequest {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(uuid);
        Objects.requireNonNull(platform);
        Objects.requireNonNull(name);
        Objects.requireNonNull(firstSeen);
        Objects.requireNonNull(lastSeen);
        Objects.requireNonNull(state);
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be positive");
        }
    }

    public ApprovalRequest attempted(String latestName, Instant now) {
        return new ApprovalRequest(requestId, uuid, platform, latestName, firstSeen, now,
                attempts == Integer.MAX_VALUE ? attempts : attempts + 1, state,
                stateChangedAt, actor, discordMessageId, lastNotified);
    }

    public ApprovalRequest notified(Instant now) {
        return new ApprovalRequest(requestId, uuid, platform, name, firstSeen, lastSeen,
                attempts, state, stateChangedAt, actor, discordMessageId, now);
    }

    public ApprovalRequest withMessageId(String messageId) {
        return new ApprovalRequest(requestId, uuid, platform, name, firstSeen, lastSeen,
                attempts, state, stateChangedAt, actor, messageId, lastNotified);
    }

    public ApprovalRequest transitioned(RequestState next, String decidedBy, Instant now) {
        if (state != RequestState.PENDING || next == RequestState.PENDING) {
            throw new IllegalStateException("invalid request state transition");
        }
        return new ApprovalRequest(requestId, uuid, platform, name, firstSeen, lastSeen,
                attempts, next, now, decidedBy, discordMessageId, lastNotified);
    }
}
