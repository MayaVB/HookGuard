package io.hookguard.demo;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

/** In-memory state of the simulator: outage switch, flaky counters and a log of recent requests. */
@Component
public class ReceiverState {

    private static final int MAX_LOG_ENTRIES = 200;

    public record ReceivedRequest(
            Instant receivedAt,
            String path,
            String deliveryId,
            String eventType,
            String attempt,
            int respondedStatus,
            boolean duplicate,
            String bodyPreview) {
    }

    private final AtomicBoolean outage = new AtomicBoolean(false);
    private final Map<String, Integer> requestsPerDelivery = new ConcurrentHashMap<>();
    private final Set<String> acknowledgedDeliveries = ConcurrentHashMap.newKeySet();
    private final Deque<ReceivedRequest> log = new ArrayDeque<>();

    public boolean outage() {
        return outage.get();
    }

    public void setOutage(boolean enabled) {
        outage.set(enabled);
    }

    /** Counts requests per delivery ID (1 for the first request), used by the flaky behaviour. */
    public int countRequest(String deliveryId) {
        return requestsPerDelivery.merge(deliveryId, 1, Integer::sum);
    }

    /**
     * Records a request and returns whether it was a duplicate: a delivery this receiver had already
     * acknowledged with 2xx. This is what a real receiver must deduplicate on X-HookGuard-Delivery-Id.
     */
    public synchronized boolean record(String path, String deliveryId, String eventType, String attempt,
                                       int status, String body) {
        boolean duplicate = deliveryId != null && acknowledgedDeliveries.contains(deliveryId);
        if (deliveryId != null && status >= 200 && status < 300) {
            acknowledgedDeliveries.add(deliveryId);
        }
        log.addFirst(new ReceivedRequest(Instant.now(), path, deliveryId, eventType, attempt, status, duplicate,
                preview(body)));
        while (log.size() > MAX_LOG_ENTRIES) {
            log.removeLast();
        }
        return duplicate;
    }

    public synchronized List<ReceivedRequest> recent(int limit) {
        return log.stream().limit(limit).toList();
    }

    public synchronized int logSize() {
        return log.size();
    }

    public synchronized void clear() {
        log.clear();
        requestsPerDelivery.clear();
        acknowledgedDeliveries.clear();
    }

    private static String preview(String body) {
        if (body == null) {
            return null;
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }
}
