package io.hookguard.delivery.worker;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import io.hookguard.config.HookGuardProperties;
import io.hookguard.delivery.http.WebhookRequest;
import io.hookguard.delivery.http.WebhookResult;
import io.hookguard.delivery.http.WebhookSender;
import io.hookguard.security.TargetUrlPolicy;

/**
 * Processes one delivery message in three explicit steps:
 *
 * <ol>
 *   <li><b>Claim</b> (short transaction): atomic PENDING -> IN_PROGRESS. Losing the claim means
 *       another worker has it or it is already done, so the message is a duplicate and is dropped.</li>
 *   <li><b>Send</b> (no transaction): the HTTP call. No database transaction or connection is held
 *       while waiting on the network, so a slow endpoint cannot exhaust the connection pool or
 *       hold row locks for the length of a timeout.</li>
 *   <li><b>Record</b> (short transaction): insert the attempt and apply the fenced transition.</li>
 * </ol>
 *
 * <p>At-least-once window: if the process dies after step 2 succeeds but before step 3 commits,
 * the receiver has the webhook but HookGuard has no record of it. That delivery will be attempted
 * again (after its lease expires, via the recovery added in Phase 2), and the receiver sees it
 * twice. This is why every request carries a stable X-HookGuard-Delivery-Id header.
 */
@Component
@ConditionalOnBooleanProperty(name = "hookguard.worker.enabled", matchIfMissing = true)
public class DeliveryProcessor {

    private static final Logger log = LoggerFactory.getLogger(DeliveryProcessor.class);

    private final DeliveryStateStore stateStore;
    private final WebhookSender sender;
    private final TargetUrlPolicy targetPolicy;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final String workerId;
    private final Duration leaseDuration;

    public DeliveryProcessor(DeliveryStateStore stateStore, WebhookSender sender, TargetUrlPolicy targetPolicy,
                             TransactionTemplate transaction, Clock clock, HookGuardProperties properties) {
        this.stateStore = stateStore;
        this.sender = sender;
        this.targetPolicy = targetPolicy;
        this.transaction = transaction;
        this.clock = clock;
        this.workerId = properties.worker().effectiveId();
        this.leaseDuration = properties.worker().leaseDuration();
    }

    public ProcessingOutcome process(UUID deliveryId) {
        // 1. Claim
        Optional<ClaimedDelivery> claimed = inTransaction(() -> stateStore.tryClaim(deliveryId, workerId, leaseDuration));
        if (claimed.isEmpty()) {
            log.info("Skipping delivery {}: not claimable (in progress elsewhere, already finished, not yet due, or unknown)",
                    deliveryId);
            return ProcessingOutcome.SKIPPED;
        }
        ClaimedDelivery claim = claimed.get();
        log.info("Claimed delivery {} attempt {} -> {}", claim.deliveryId(), claim.attemptNumber(), claim.targetUrl());

        // 2. Send (outside any transaction)
        WebhookResult result = attempt(claim);

        // 3. Record
        boolean applied = inTransaction(() -> {
            stateStore.recordAttempt(claim, workerId, result);
            return result.successful()
                    ? stateStore.markDelivered(claim, result.httpStatus())
                    : stateStore.markFailed(claim, result.httpStatus(), result.failureSummary());
        });

        if (!applied) {
            log.warn("Delivery {} attempt {} finished with {} but the claim was no longer held; "
                    + "recorded as history only, state left to the current claim holder",
                    claim.deliveryId(), claim.attemptNumber(), result.outcome());
            return ProcessingOutcome.CLAIM_LOST;
        }
        if (result.successful()) {
            log.info("Delivery {} DELIVERED (HTTP {}, {} ms)", claim.deliveryId(), result.httpStatus(), result.durationMs());
            return ProcessingOutcome.DELIVERED;
        }
        log.warn("Delivery {} FAILED on attempt {}: {}", claim.deliveryId(), claim.attemptNumber(), result.failureSummary());
        return ProcessingOutcome.FAILED;
    }

    private WebhookResult attempt(ClaimedDelivery claim) {
        // Re-check at send time: the allowlist may have been tightened since the endpoint was registered.
        Optional<String> violation = targetPolicy.violation(claim.targetUrl());
        if (violation.isPresent()) {
            return WebhookResult.rejected("Target no longer allowed: " + violation.get(), clock.instant());
        }
        return sender.send(new WebhookRequest(
                claim.targetUrl(), claim.payload(), claim.deliveryId(), claim.eventId(), claim.eventType(),
                claim.attemptNumber()));
    }

    private <T> T inTransaction(Supplier<T> work) {
        T result = transaction.execute(status -> work.get());
        if (result == null) {
            throw new IllegalStateException("Transaction callback returned null");
        }
        return result;
    }
}
