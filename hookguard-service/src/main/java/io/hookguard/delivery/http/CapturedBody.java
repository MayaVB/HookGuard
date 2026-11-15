package io.hookguard.delivery.http;

/** The (possibly truncated) response body kept for diagnostics. */
record CapturedBody(String text, boolean truncated) {
}
