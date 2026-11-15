package io.hookguard.delivery.api;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.hookguard.api.ResourceNotFoundException;
import io.hookguard.delivery.Delivery;
import io.hookguard.delivery.DeliveryAttemptRepository;
import io.hookguard.delivery.DeliveryRepository;
import io.hookguard.delivery.DeliveryStatus;

@Service
@Transactional(readOnly = true)
public class DeliveryQueryService {

    private final DeliveryRepository deliveries;
    private final DeliveryAttemptRepository attempts;

    public DeliveryQueryService(DeliveryRepository deliveries, DeliveryAttemptRepository attempts) {
        this.deliveries = deliveries;
        this.attempts = attempts;
    }

    public PageResponse<DeliverySummary> list(DeliveryStatus status, int page, int size) {
        return PageResponse.from(deliveries.findSummaries(status, PageRequest.of(page, size)));
    }

    public DeliveryDetail get(UUID id) {
        Delivery d = deliveries.findWithEventAndEndpoint(id)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery", id));
        List<DeliveryDetail.Attempt> history = attempts.findByDeliveryIdOrderByAttemptNumberAsc(id).stream()
                .map(a -> new DeliveryDetail.Attempt(a.getAttemptNumber(), a.getWorkerId(), a.getStartedAt(),
                        a.getFinishedAt(), a.getDurationMs(), a.getOutcome(), a.getHttpStatus(), a.getResponseBody(),
                        a.isResponseTruncated(), a.getErrorMessage()))
                .toList();
        return new DeliveryDetail(
                d.getId(), d.getStatus(), d.getAttemptCount(), d.getNextAttemptAt(), d.getClaimedBy(),
                d.getClaimExpiresAt(), deliveries.isLeaseExpired(id), d.getLastHttpStatus(), d.getLastError(),
                d.getCreatedAt(), d.getUpdatedAt(), d.getCompletedAt(),
                new DeliveryDetail.EventInfo(d.getEvent().getId(), d.getEvent().getEventType(),
                        d.getEvent().getCreatedAt(), d.getEvent().getPayload()),
                new DeliveryDetail.EndpointInfo(d.getEndpoint().getId(), d.getEndpoint().getUrl(),
                        d.getEndpoint().getDescription()),
                history);
    }

    public StatsResponse stats() {
        Map<DeliveryStatus, Long> byStatus = new EnumMap<>(DeliveryStatus.class);
        for (DeliveryStatus status : DeliveryStatus.values()) {
            byStatus.put(status, 0L);
        }
        long total = 0;
        for (StatusCount row : deliveries.countByStatus()) {
            byStatus.put(row.status(), row.count());
            total += row.count();
        }
        return new StatsResponse(total, byStatus);
    }
}
