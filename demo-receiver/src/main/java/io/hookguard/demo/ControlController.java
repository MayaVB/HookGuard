package io.hookguard.demo;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime controls for live demos:
 *
 * <pre>
 *   GET    /control                     current state
 *   PUT    /control/outage {"enabled":true|false}   take the whole receiver "down" / bring it back
 *   GET    /received?limit=50           recent requests, newest first (duplicates flagged)
 *   DELETE /received                    clear the log and counters
 * </pre>
 */
@RestController
public class ControlController {

    private final ReceiverState state;

    public ControlController(ReceiverState state) {
        this.state = state;
    }

    public record OutageRequest(boolean enabled) {
    }

    @GetMapping("/control")
    public Map<String, Object> control() {
        return Map.of("outage", state.outage(), "loggedRequests", state.logSize());
    }

    @PutMapping("/control/outage")
    public Map<String, Object> setOutage(@RequestBody OutageRequest request) {
        state.setOutage(request.enabled());
        return control();
    }

    @GetMapping("/received")
    public List<ReceiverState.ReceivedRequest> received(@RequestParam(defaultValue = "50") int limit) {
        return state.recent(Math.clamp(limit, 1, 200));
    }

    @DeleteMapping("/received")
    public ResponseEntity<Void> clear() {
        state.clear();
        return ResponseEntity.noContent().build();
    }
}
