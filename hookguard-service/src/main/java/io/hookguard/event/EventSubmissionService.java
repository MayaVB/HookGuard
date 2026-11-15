package io.hookguard.event;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.hookguard.api.InvalidFieldException;
import io.hookguard.api.PayloadTooLargeException;
import io.hookguard.api.ResourceNotFoundException;
import io.hookguard.config.HookGuardProperties;
import io.hookguard.delivery.Delivery;
import io.hookguard.delivery.DeliveryCreated;
import io.hookguard.delivery.DeliveryRepository;
import io.hookguard.delivery.DeliveryStatus;
import io.hookguard.endpoint.Endpoint;
import io.hookguard.endpoint.EndpointRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Accepts an event: stores the event and its delivery in one transaction, then (after commit)
 * hands the delivery ID to RabbitMQ. Once this method returns, the event is durable; whether it
 * is delivered quickly depends on the broker, but it is never lost by HookGuard's database.
 */
@Service
public class EventSubmissionService {

    private final EndpointRepository endpoints;
    private final EventRepository events;
    private final DeliveryRepository deliveries;
    private final ApplicationEventPublisher applicationEvents;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final int maxPayloadBytes;

    public EventSubmissionService(EndpointRepository endpoints, EventRepository events, DeliveryRepository deliveries,
                                  ApplicationEventPublisher applicationEvents, JsonMapper jsonMapper, Clock clock,
                                  HookGuardProperties properties) {
        this.endpoints = endpoints;
        this.events = events;
        this.deliveries = deliveries;
        this.applicationEvents = applicationEvents;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.maxPayloadBytes = properties.api().maxPayloadBytes();
    }

    @Transactional
    public EventAcceptedResponse submit(SubmitEventRequest request) {
        if (!request.payload().isObject()) {
            throw new InvalidFieldException("payload", "must be a JSON object");
        }
        String payload = jsonMapper.writeValueAsString(request.payload());
        int payloadBytes = payload.getBytes(StandardCharsets.UTF_8).length;
        if (payloadBytes > maxPayloadBytes) {
            throw new PayloadTooLargeException(payloadBytes, maxPayloadBytes);
        }

        Endpoint endpoint = endpoints.findById(request.endpointId())
                .orElseThrow(() -> new ResourceNotFoundException("Endpoint", request.endpointId()));

        Event event = events.save(new Event(request.eventType(), payload, clock.instant()));
        Delivery delivery = deliveries.save(Delivery.pendingFor(event, endpoint));

        // Delivered to DeliveryPublisher only if this transaction commits.
        applicationEvents.publishEvent(new DeliveryCreated(delivery.getId()));

        return new EventAcceptedResponse(event.getId(), delivery.getId(), DeliveryStatus.PENDING);
    }
}
