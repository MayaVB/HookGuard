package io.hookguard.delivery.http;

import java.time.Instant;

import io.hookguard.delivery.AttemptOutcome;

/** What happened during one attempt. Exactly one of {@code httpStatus} / {@code errorMessage} is usually set. */
public record WebhookResult(
        AttemptOutcome outcome,
        Integer httpStatus,
        String responseBody,
        boolean responseTruncated,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        long durationMs) {

    public boolean successful() {
        return outcome == AttemptOutcome.SUCCESS;
    }

    /** Short description of why the attempt failed, for {@code deliveries.last_error}. */
    public String failureSummary() {
        return switch (outcome) {
            case SUCCESS -> null;
            case HTTP_ERROR -> "Endpoint responded with HTTP " + httpStatus;
            case TIMEOUT, CONNECTION_ERROR, REJECTED -> errorMessage;
        };
    }

    static WebhookResult response(int status, CapturedBody body, Instant startedAt, Instant finishedAt, long durationMs) {
        AttemptOutcome outcome = status >= 200 && status < 300 ? AttemptOutcome.SUCCESS : AttemptOutcome.HTTP_ERROR;
        return new WebhookResult(outcome, status, body.text(), body.truncated(), null, startedAt, finishedAt, durationMs);
    }

    static WebhookResult error(AttemptOutcome outcome, String message, Instant startedAt, Instant finishedAt, long durationMs) {
        return new WebhookResult(outcome, null, null, false, message, startedAt, finishedAt, durationMs);
    }

    /** No request was made because the target is not allowed. */
    public static WebhookResult rejected(String reason, Instant at) {
        return new WebhookResult(AttemptOutcome.REJECTED, null, null, false, reason, at, at, 0);
    }
}
