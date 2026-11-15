package io.hookguard.delivery.api;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.hookguard.delivery.DeliveryStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api")
@ConditionalOnBooleanProperty(name = "hookguard.api.enabled", matchIfMissing = true)
public class DeliveryController {

    private final DeliveryQueryService queryService;

    public DeliveryController(DeliveryQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/deliveries")
    public PageResponse<DeliverySummary> list(
            @RequestParam(required = false) DeliveryStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return queryService.list(status, page, size);
    }

    @GetMapping("/deliveries/{id}")
    public DeliveryDetail get(@PathVariable UUID id) {
        return queryService.get(id);
    }

    @GetMapping("/stats")
    public StatsResponse stats() {
        return queryService.stats();
    }
}
