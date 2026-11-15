package io.hookguard.delivery.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

import io.hookguard.delivery.AttemptOutcome;
import io.hookguard.delivery.DeliveryStatus;
import io.hookguard.delivery.http.WebhookResult;
import io.hookguard.support.IntegrationTestBase;

/**
 * The atomic claim and fenced completion, tested directly against PostgreSQL.
 * Deliveries are inserted with SQL (no RabbitMQ message), so the running worker never touches them.
 */
class DeliveryClaimIT extends IntegrationTestBase {

    private static final Duration LEASE = Duration.ofSeconds(30);

    @Autowired
    private DeliveryStateStore store;

    @Autowired
    private TransactionTemplate transaction;

    private final ExecutorService pool = Executors.newFixedThreadPool(16);

    @AfterEach
    void shutdownPool() {
        pool.shutdownNow();
    }

    @Test
    void exactlyOneOfManyConcurrentClaimersWins() throws Exception {
        int rounds = 25;
        int claimers = 16;
        for (int round = 0; round < rounds; round++) {
            UUID deliveryId = insertPendingDelivery();
            CountDownLatch ready = new CountDownLatch(claimers);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Optional<ClaimedDelivery>>> results = new ArrayList<>();
            for (int i = 0; i < claimers; i++) {
                String workerId = "worker-" + i;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return claim(deliveryId, workerId);
                }));
            }
            ready.await();
            go.countDown();

            List<ClaimedDelivery> winners = new ArrayList<>();
            for (Future<Optional<ClaimedDelivery>> result : results) {
                result.get(10, TimeUnit.SECONDS).ifPresent(winners::add);
            }

            assertThat(winners).as("winners in round %d", round).hasSize(1);
            ClaimedDelivery winner = winners.getFirst();
            assertThat(winner.attemptNumber()).isEqualTo(1);
            assertThat(statusOf(deliveryId)).isEqualTo(DeliveryStatus.IN_PROGRESS);
            assertThat(storedClaimToken(deliveryId)).isEqualTo(winner.claimToken());
            assertThat(storedAttemptCount(deliveryId)).isEqualTo(1);
        }
    }

    /**
     * Deterministic version of the race: claimer A has updated the row but not committed.
     * Claimer B must block on the row lock, then re-check the WHERE clause after A commits,
     * and lose. This is the PostgreSQL READ COMMITTED behaviour the claim relies on.
     */
    @Test
    void secondClaimerBlocksOnRowLockThenLosesAfterFirstCommits() throws Exception {
        UUID deliveryId = insertPendingDelivery();
        CountDownLatch aClaimedButNotCommitted = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);

        CompletableFuture<Optional<ClaimedDelivery>> claimerA = CompletableFuture.supplyAsync(() ->
                transaction.execute(status -> {
                    Optional<ClaimedDelivery> claimed = store.tryClaim(deliveryId, "worker-A", LEASE);
                    aClaimedButNotCommitted.countDown();
                    await(releaseA);
                    return claimed;
                }), pool);
        assertThat(aClaimedButNotCommitted.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Optional<ClaimedDelivery>> claimerB =
                CompletableFuture.supplyAsync(() -> claim(deliveryId, "worker-B"), pool);

        Thread.sleep(500);
        assertThat(claimerB).as("B must wait for A's row lock").isNotDone();

        releaseA.countDown();
        assertThat(claimerA.get(5, TimeUnit.SECONDS)).isPresent();
        assertThat(claimerB.get(5, TimeUnit.SECONDS)).as("B re-evaluates WHERE status='PENDING' and loses").isEmpty();
    }

    @Test
    void completionIsAppliedExactlyOnce() {
        UUID deliveryId = insertPendingDelivery();
        ClaimedDelivery claim = claim(deliveryId, "worker-A").orElseThrow();

        assertThat(inTransaction(() -> store.markDelivered(claim, 200))).isTrue();
        assertThat(inTransaction(() -> store.markFailed(claim, 500, "late duplicate"))).isFalse();

        assertThat(statusOf(deliveryId)).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(storedClaimToken(deliveryId)).isNull();
    }

    /**
     * Fencing: worker A's lease expired and the delivery was reclaimed by worker B (simulated here
     * by replacing the claim; Phase 2 adds the real recovery). A's late result must not overwrite B's claim.
     */
    @Test
    void staleClaimHolderCannotOverwriteNewerClaim() {
        UUID deliveryId = insertPendingDelivery();
        ClaimedDelivery staleClaim = claim(deliveryId, "worker-A").orElseThrow();
        UUID newerToken = UUID.randomUUID();
        jdbc.sql("UPDATE deliveries SET claim_token = ?, claimed_by = 'worker-B', attempt_count = 2 WHERE id = ?")
                .params(newerToken, deliveryId).update();

        boolean applied = inTransaction(() -> store.markDelivered(staleClaim, 200));

        assertThat(applied).isFalse();
        assertThat(statusOf(deliveryId)).isEqualTo(DeliveryStatus.IN_PROGRESS);
        assertThat(storedClaimToken(deliveryId)).isEqualTo(newerToken);
    }

    @Test
    void finishedUnknownAndNotYetDueDeliveriesAreNotClaimable() {
        UUID finished = insertPendingDelivery();
        ClaimedDelivery claim = claim(finished, "worker-A").orElseThrow();
        inTransaction(() -> store.markDelivered(claim, 200));

        UUID notYetDue = insertPendingDelivery();
        jdbc.sql("UPDATE deliveries SET next_attempt_at = now() + interval '1 hour' WHERE id = ?").param(notYetDue).update();

        assertThat(claim(finished, "worker-B")).isEmpty();
        assertThat(claim(UUID.randomUUID(), "worker-B")).isEmpty();
        assertThat(claim(notYetDue, "worker-B")).isEmpty();
    }

    @Test
    void databaseRejectsInProgressWithoutClaim() {
        UUID deliveryId = insertPendingDelivery();

        assertThatThrownBy(() -> jdbc.sql("UPDATE deliveries SET status = 'IN_PROGRESS' WHERE id = ?")
                .param(deliveryId).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("deliveries_claim_matches_status");
    }

    @Test
    void sameAttemptNumberCannotBeRecordedTwice() {
        UUID deliveryId = insertPendingDelivery();
        ClaimedDelivery claim = claim(deliveryId, "worker-A").orElseThrow();
        WebhookResult result = new WebhookResult(AttemptOutcome.SUCCESS, 200, "ok", false, null,
                Instant.now(), Instant.now(), 5);

        inTransaction(() -> {
            store.recordAttempt(claim, "worker-A", result);
            return true;
        });

        assertThatThrownBy(() -> inTransaction(() -> {
            store.recordAttempt(claim, "worker-A", result);
            return true;
        })).isInstanceOf(DuplicateKeyException.class);
    }

    private Optional<ClaimedDelivery> claim(UUID deliveryId, String workerId) {
        return inTransaction(() -> store.tryClaim(deliveryId, workerId, LEASE));
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    private UUID insertPendingDelivery() {
        UUID endpointId = jdbc.sql("INSERT INTO endpoints (url) VALUES (?) RETURNING id")
                .param(stub.url("/claim-test")).query(UUID.class).single();
        UUID eventId = jdbc.sql("INSERT INTO events (event_type, payload) VALUES ('test.event', '{\"n\":1}'::json) RETURNING id")
                .query(UUID.class).single();
        return jdbc.sql("INSERT INTO deliveries (event_id, endpoint_id, status) VALUES (?, ?, 'PENDING') RETURNING id")
                .params(eventId, endpointId).query(UUID.class).single();
    }

    private UUID storedClaimToken(UUID deliveryId) {
        // list() rather than single(): the token is legitimately NULL once a delivery is finished.
        return jdbc.sql("SELECT claim_token FROM deliveries WHERE id = ?").param(deliveryId)
                .query((rs, n) -> rs.getObject(1, UUID.class)).list().getFirst();
    }

    private int storedAttemptCount(UUID deliveryId) {
        return jdbc.sql("SELECT attempt_count FROM deliveries WHERE id = ?").param(deliveryId)
                .query(Integer.class).single();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
