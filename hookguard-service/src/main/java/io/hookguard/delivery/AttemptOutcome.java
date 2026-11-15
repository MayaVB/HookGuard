package io.hookguard.delivery;

/** Result category of one delivery attempt. */
public enum AttemptOutcome {
    /** Endpoint answered with a 2xx status. */
    SUCCESS,
    /** Endpoint answered with a non-2xx status (redirects are not followed and count here). */
    HTTP_ERROR,
    /** Connected, but no complete response within the request timeout. */
    TIMEOUT,
    /** Could not connect or the connection broke (refused, reset, DNS failure, connect timeout). */
    CONNECTION_ERROR,
    /** No request was sent because the target URL is not allowed by the current policy. */
    REJECTED
}
