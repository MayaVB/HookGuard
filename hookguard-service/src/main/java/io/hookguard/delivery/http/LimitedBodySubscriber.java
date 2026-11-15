package io.hookguard.delivery.http;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Reads at most {@code limit} bytes of a response body, then cancels the stream.
 *
 * <p>We only keep the body for diagnostics. Reading it fully would let a misbehaving
 * endpoint make the worker buffer an arbitrarily large response in memory.
 */
final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<CapturedBody> {

    private final int limit;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final CompletableFuture<CapturedBody> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    LimitedBodySubscriber(int limit) {
        this.limit = limit;
    }

    @Override
    public CompletionStage<CapturedBody> getBody() {
        return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
        for (ByteBuffer item : items) {
            int keep = Math.min(limit - buffer.size(), item.remaining());
            byte[] chunk = new byte[keep];
            item.get(chunk);
            buffer.write(chunk, 0, keep);
            if (item.hasRemaining()) {
                subscription.cancel();
                result.complete(new CapturedBody(decode(), true));
                return;
            }
        }
        subscription.request(1);
    }

    @Override
    public void onError(Throwable throwable) {
        result.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
        result.complete(new CapturedBody(decode(), false));
    }

    private String decode() {
        // Malformed or cut-off UTF-8 sequences are replaced, never thrown.
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
