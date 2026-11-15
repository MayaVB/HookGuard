package io.hookguard.endpoint;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/endpoints")
@ConditionalOnBooleanProperty(name = "hookguard.api.enabled", matchIfMissing = true)
public class EndpointController {

    private final EndpointService endpointService;

    public EndpointController(EndpointService endpointService) {
        this.endpointService = endpointService;
    }

    @PostMapping
    public ResponseEntity<EndpointResponse> register(@Valid @RequestBody CreateEndpointRequest request) {
        EndpointResponse created = endpointService.register(request);
        return ResponseEntity.created(URI.create("/api/endpoints/" + created.id())).body(created);
    }

    @GetMapping
    public List<EndpointResponse> list() {
        return endpointService.list();
    }

    @GetMapping("/{id}")
    public EndpointResponse get(@PathVariable UUID id) {
        return endpointService.get(id);
    }
}
