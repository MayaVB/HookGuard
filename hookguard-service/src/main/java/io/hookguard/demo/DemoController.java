package io.hookguard.demo;

import java.util.UUID;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.hookguard.api.ResourceNotFoundException;
import io.hookguard.config.HookGuardProperties;
import io.hookguard.delivery.DeliveryRepository;
import io.hookguard.messaging.RabbitTopology;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Demo-only endpoints for the dashboard's live demo page. Exists only when
 * {@code hookguard.demo.enabled=true} (HOOKGUARD_DEMO_ENABLED); never enable it in production.
 */
@RestController
@RequestMapping("/api/demo")
@ConditionalOnExpression("${hookguard.api.enabled:true} and ${hookguard.demo.enabled:false}")
public class DemoController {

    private final RabbitTemplate rabbitTemplate;
    private final DeliveryRepository deliveries;
    private final String receiverBaseUrl;

    public DemoController(RabbitTemplate rabbitTemplate, DeliveryRepository deliveries, HookGuardProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.deliveries = deliveries;
        this.receiverBaseUrl = properties.demo().receiverBaseUrl();
    }

    public record DemoInfo(String receiverBaseUrl) {
    }

    public record DuplicatesRequest(@Min(1) @Max(50) int copies) {
    }

    public record DuplicatesPublished(UUID deliveryId, int copies) {
    }

    @GetMapping
    public DemoInfo info() {
        return new DemoInfo(receiverBaseUrl);
    }

    /**
     * Publishes extra copies of an existing delivery's work message, simulating broker redelivery
     * or a re-publishing relay. The atomic claim must turn all of them into no-ops.
     */
    @PostMapping("/deliveries/{id}/duplicates")
    public DuplicatesPublished publishDuplicates(@PathVariable UUID id, @Valid @RequestBody DuplicatesRequest request) {
        if (!deliveries.existsById(id)) {
            throw new ResourceNotFoundException("Delivery", id);
        }
        for (int i = 0; i < request.copies(); i++) {
            rabbitTemplate.convertAndSend(RabbitTopology.EXCHANGE, RabbitTopology.WORK_ROUTING_KEY, id.toString());
        }
        return new DuplicatesPublished(id, request.copies());
    }
}
