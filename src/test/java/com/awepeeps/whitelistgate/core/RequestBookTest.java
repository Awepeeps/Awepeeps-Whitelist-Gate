package com.awepeeps.whitelistgate.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestBookTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration COOLDOWN = Duration.ofMinutes(5);

    @Test
    void deduplicatesWithinCooldownAndEditsAfterCooldown() {
        RequestBook book = new RequestBook(List.of(), 3, COOLDOWN,
                Duration.ofHours(1), Duration.ofHours(24));
        UUID uuid = UUID.randomUUID();

        RequestBook.AttemptResult first =
                book.recordAttempt(uuid, "FakeName", Platform.JAVA, START);
        RequestBook.AttemptResult repeat =
                book.recordAttempt(uuid, "FakeName", Platform.JAVA, START.plusSeconds(60));
        RequestBook.AttemptResult reminder =
                book.recordAttempt(uuid, "FakeName", Platform.JAVA, START.plusSeconds(301));

        assertEquals(RequestBook.Notice.NEW, first.notice());
        assertEquals(RequestBook.Notice.NONE, repeat.notice());
        assertEquals(RequestBook.Notice.EDIT, reminder.notice());
        assertEquals(3, reminder.request().attempts());
        assertEquals(1, book.pendingCount());
    }

    @Test
    void capsPendingIdentitiesAndCountsDrops() {
        RequestBook book = new RequestBook(List.of(), 1, COOLDOWN,
                Duration.ofHours(1), Duration.ofHours(24));
        book.recordAttempt(UUID.randomUUID(), "FakeOne", Platform.JAVA, START);

        RequestBook.AttemptResult overflow =
                book.recordAttempt(UUID.randomUUID(), "FakeTwo", Platform.BEDROCK, START);

        assertFalse(overflow.accepted());
        assertNull(overflow.request());
        assertEquals(1, book.droppedRequests());
        assertEquals(1, book.pendingCount());
    }

    @Test
    void expiresPendingRequestsAndAllowsLaterRerequest() {
        RequestBook book = new RequestBook(List.of(), 2, COOLDOWN,
                Duration.ofHours(1), Duration.ofHours(1));
        UUID uuid = UUID.randomUUID();
        ApprovalRequest initial =
                book.recordAttempt(uuid, "FakeName", Platform.BEDROCK, START).request();

        List<ApprovalRequest> expired = book.expire(START.plus(Duration.ofHours(1)));
        RequestBook.AttemptResult tooSoon =
                book.recordAttempt(uuid, "FakeName", Platform.BEDROCK,
                        START.plus(Duration.ofHours(1)));
        RequestBook.AttemptResult reopened =
                book.recordAttempt(uuid, "FakeName", Platform.BEDROCK,
                        START.plus(Duration.ofHours(2)));

        assertEquals(RequestState.EXPIRED, expired.getFirst().state());
        assertFalse(tooSoon.accepted());
        assertEquals(RequestBook.Notice.NEW, reopened.notice());
        assertNotNull(reopened.request());
        assertFalse(initial.requestId().equals(reopened.request().requestId()));
    }

    @Test
    void decisionsAreIdempotentAndDeniedRequestsRespectCooldown() {
        RequestBook book = new RequestBook(List.of(), 2, COOLDOWN,
                Duration.ofHours(2), Duration.ofHours(24));
        UUID uuid = UUID.randomUUID();
        ApprovalRequest pending =
                book.recordAttempt(uuid, "FakeName", Platform.JAVA, START).request();

        ApprovalRequest denied = book.transition(
                pending.requestId(), RequestState.DENIED, "CONSOLE", START.plusSeconds(10));
        ApprovalRequest replay = book.transition(
                pending.requestId(), RequestState.APPROVED, "FakeOperator", START.plusSeconds(11));
        RequestBook.AttemptResult tooSoon =
                book.recordAttempt(uuid, "FakeName", Platform.JAVA, START.plusSeconds(100));
        // The denial happened at START+10s, so the 2h cooldown ends at START+2h10s.
        RequestBook.AttemptResult afterCooldown =
                book.recordAttempt(uuid, "FakeName", Platform.JAVA,
                        START.plus(Duration.ofHours(2)).plusSeconds(10));

        assertEquals(RequestState.DENIED, denied.state());
        assertNull(replay);
        assertFalse(tooSoon.accepted());
        assertTrue(afterCooldown.accepted());
        assertEquals(RequestState.PENDING, afterCooldown.request().state());
    }
}
