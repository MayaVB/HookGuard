package io.hookguard.event;

import java.net.URI;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/events")
@ConditionalOnBooleanProperty(name = "hookguard.api.enabled", matchIfMissing = true)
public class EventController {

    private final EventSubmissionService submissionService;

    public EventController(EventSubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    /** 202 Accepted: the event is stored; delivery happens asynchronously. */
    @PostMapping
    public ResponseEntity<EventAcceptedResponse> submit(@Valid @RequestBody SubmitEventRequest request) {
        EventAcceptedResponse accepted = submissionService.submit(request);
        return ResponseEntity.accepted()
                .location(URI.create("/api/deliveries/" + accepted.deliveryId()))
                .body(accepted);
    }
}
