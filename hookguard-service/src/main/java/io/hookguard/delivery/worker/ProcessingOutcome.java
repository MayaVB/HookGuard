package io.hookguard.delivery.worker;

/** What the worker did with one message. */
public enum ProcessingOutcome {
    /** The delivery was not claimable (duplicate or stale message); nothing was sent. */
    SKIPPED,
    DELIVERED,
    FAILED,
    /** The request was sent but the claim had expired and been taken over; the result was recorded as history only. */
    CLAIM_LOST
}
