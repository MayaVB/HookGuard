package io.hookguard.delivery.worker;

import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.hookguard.delivery.DeliveryStatus;
import io.hookguard.delivery.http.WebhookResult;

/**
 * The worker's write path: every delivery state transition is a single conditional UPDATE
 * (compare-and-set) whose WHERE clause states the state it expects to move from.
 *
 * <p>Why explicit SQL instead of loading the entity, changing it and saving it: a
 * read-modify-write sequence lets two workers both read PENDING and both proceed. A
 * conditional UPDATE is checked and applied atomically by PostgreSQL, and the number of
 * updated rows tells us whether we won.
 *
 * <p>Callers own the transaction boundaries (see DeliveryProcessor).
 */
@Repository
public class DeliveryStateStore {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcClient jdbc;

    public DeliveryStateStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Atomically moves a delivery from PENDING to IN_PROGRESS and returns what the worker needs
     * to perform the attempt. Returns empty if this worker did not win the claim: the delivery
     * is already claimed, already finished, not yet due, or does not exist.
     *
     * <p>Concurrency: under READ COMMITTED, if two transactions run this UPDATE on the same row,
     * the second blocks on the row lock until the first commits, then re-evaluates the WHERE
     * clause against the new row version (status is now IN_PROGRESS) and updates nothing.
     * Exactly one claimer wins, without SELECT ... FOR UPDATE or application-level locks.
     */
    public Optional<ClaimedDelivery> tryClaim(UUID deliveryId, String workerId, Duration lease) {
        UUID claimToken = UUID.randomUUID();
        return jdbc.sql("""
                        WITH claimed AS (
                            UPDATE deliveries
                               SET status           = 'IN_PROGRESS',
                                   claim_token      = :claimToken,
                                   claimed_by       = :workerId,
                                   claim_expires_at = now() + make_interval(secs => :leaseSeconds),
                                   attempt_count    = attempt_count + 1,
                                   updated_at       = now()
                             WHERE id = :deliveryId
                               AND status = 'PENDING'
                               AND next_attempt_at <= now()
                         RETURNING id, event_id, endpoint_id, attempt_count, claim_token
                        )
                        SELECT c.id, c.claim_token, c.attempt_count,
                               ev.id AS event_id, ev.event_type, ev.payload::text AS payload,
                               en.url
                          FROM claimed c
                          JOIN events ev ON ev.id = c.event_id
                          JOIN endpoints en ON en.id = c.endpoint_id
                        """)
                .param("claimToken", claimToken)
                .param("workerId", workerId)
                .param("leaseSeconds", lease.toMillis() / 1000.0)
                .param("deliveryId", deliveryId)
                .query((rs, rowNum) -> new ClaimedDelivery(
                        rs.getObject("id", UUID.class),
                        rs.getObject("claim_token", UUID.class),
                        rs.getInt("attempt_count"),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getString("url")))
                .optional();
    }

    /** Appends the attempt to the history. Recorded even if the claim was lost: the request did happen. */
    public void recordAttempt(ClaimedDelivery claim, String workerId, WebhookResult result) {
        jdbc.sql("""
                        INSERT INTO delivery_attempts
                            (delivery_id, attempt_number, worker_id, started_at, finished_at, duration_ms,
                             outcome, http_status, response_body, response_truncated, error_message)
                        VALUES
                            (:deliveryId, :attemptNumber, :workerId, :startedAt, :finishedAt, :durationMs,
                             :outcome, :httpStatus, :responseBody, :responseTruncated, :errorMessage)
                        """)
                .param("deliveryId", claim.deliveryId())
                .param("attemptNumber", claim.attemptNumber())
                .param("workerId", workerId)
                .param("startedAt", utc(result.startedAt()))
                .param("finishedAt", utc(result.finishedAt()))
                .param("durationMs", result.durationMs())
                .param("outcome", result.outcome().name())
                .param("httpStatus", result.httpStatus(), Types.INTEGER)
                .param("responseBody", result.responseBody(), Types.VARCHAR)
                .param("responseTruncated", result.responseTruncated())
                .param("errorMessage", truncate(result.errorMessage()), Types.VARCHAR)
                .update();
    }

    /** IN_PROGRESS -> DELIVERED, only if this worker still holds the claim. */
    public boolean markDelivered(ClaimedDelivery claim, Integer httpStatus) {
        return finish(claim, DeliveryStatus.DELIVERED, httpStatus, null);
    }

    /** IN_PROGRESS -> FAILED, only if this worker still holds the claim. */
    public boolean markFailed(ClaimedDelivery claim, Integer httpStatus, String error) {
        return finish(claim, DeliveryStatus.FAILED, httpStatus, error);
    }

    /**
     * Fenced completion: the claim token in the WHERE clause guarantees that a worker whose
     * lease expired (and whose delivery may since have been reclaimed by another worker) cannot
     * overwrite the newer state. Returns false if the claim was lost.
     */
    private boolean finish(ClaimedDelivery claim, DeliveryStatus terminalStatus, Integer httpStatus, String error) {
        int updated = jdbc.sql("""
                        UPDATE deliveries
                           SET status           = :status,
                               claim_token      = NULL,
                               claimed_by       = NULL,
                               claim_expires_at = NULL,
                               last_http_status = :httpStatus,
                               last_error       = :lastError,
                               completed_at     = now(),
                               updated_at       = now()
                         WHERE id = :deliveryId
                           AND status = 'IN_PROGRESS'
                           AND claim_token = :claimToken
                        """)
                .param("status", terminalStatus.name())
                .param("httpStatus", httpStatus, Types.INTEGER)
                .param("lastError", truncate(error), Types.VARCHAR)
                .param("deliveryId", claim.deliveryId())
                .param("claimToken", claim.claimToken())
                .update();
        return updated == 1;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH - 3) + "...";
    }
}
