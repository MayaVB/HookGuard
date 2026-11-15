package io.hookguard.endpoint;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.hookguard.api.ResourceNotFoundException;
import io.hookguard.security.TargetUrlPolicy;

@Service
public class EndpointService {

    private final EndpointRepository endpoints;
    private final TargetUrlPolicy targetPolicy;
    private final Clock clock;

    public EndpointService(EndpointRepository endpoints, TargetUrlPolicy targetPolicy, Clock clock) {
        this.endpoints = endpoints;
        this.targetPolicy = targetPolicy;
        this.clock = clock;
    }

    @Transactional
    public EndpointResponse register(CreateEndpointRequest request) {
        String url = request.url().trim();
        targetPolicy.validate(url);
        String description = request.description() == null || request.description().isBlank()
                ? null : request.description().trim();
        Endpoint saved = endpoints.save(new Endpoint(url, description, clock.instant()));
        return EndpointResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<EndpointResponse> list() {
        return endpoints.findAllByOrderByCreatedAtDesc().stream().map(EndpointResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public EndpointResponse get(UUID id) {
        return endpoints.findById(id).map(EndpointResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Endpoint", id));
    }
}
