package io.hookguard.demo;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook endpoints with fixed, path-selected behaviour, so several HookGuard endpoints can
 * behave differently at the same time:
 *
 * <pre>
 *   POST /hooks/ok                    -> 200
 *   POST /hooks/fail                  -> 500
 *   POST /hooks/status/{code}         -> that status (200-599)
 *   POST /hooks/slow?delayMs=15000    -> waits, then 200 (use a delay above HookGuard's timeout)
 *   POST /hooks/flaky?failTimes=2     -> 503 for the first N requests of each delivery, then 200
 * </pre>
 *
 * While the outage switch is on (see ControlController), every /hooks request returns 503.
 */
@RestController
@RequestMapping("/hooks")
public class HookController {

    private static final Logger log = LoggerFactory.getLogger(HookController.class);
    private static final long MAX_DELAY_MS = 120_000;

    private final ReceiverState state;

    public HookController(ReceiverState state) {
        this.state = state;
    }

    @PostMapping("/ok")
    public ResponseEntity<Map<String, Object>> ok(@RequestHeader Map<String, String> headers,
                                                  @RequestBody(required = false) String body) {
        return respond("/hooks/ok", headers, body, HttpStatus.OK.value());
    }

    @PostMapping("/fail")
    public ResponseEntity<Map<String, Object>> fail(@RequestHeader Map<String, String> headers,
                                                    @RequestBody(required = false) String body) {
        return respond("/hooks/fail", headers, body, HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    @PostMapping("/status/{code}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable int code, @RequestHeader Map<String, String> headers,
                                                      @RequestBody(required = false) String body) {
        if (code < 200 || code > 599) {
            return ResponseEntity.badRequest().body(Map.of("error", "status must be between 200 and 599"));
        }
        return respond("/hooks/status/" + code, headers, body, code);
    }

    @PostMapping("/slow")
    public ResponseEntity<Map<String, Object>> slow(@RequestParam(defaultValue = "15000") long delayMs,
                                                    @RequestHeader Map<String, String> headers,
                                                    @RequestBody(required = false) String body) throws InterruptedException {
        if (!state.outage()) {
            Thread.sleep(Math.clamp(delayMs, 0, MAX_DELAY_MS));
        }
        return respond("/hooks/slow", headers, body, HttpStatus.OK.value());
    }

    @PostMapping("/flaky")
    public ResponseEntity<Map<String, Object>> flaky(@RequestParam(defaultValue = "2") int failTimes,
                                                     @RequestHeader Map<String, String> headers,
                                                     @RequestBody(required = false) String body) {
        String key = deliveryId(headers) != null ? deliveryId(headers) : "no-delivery-id";
        int requestNumber = state.outage() ? 0 : state.countRequest(key);
        int status = requestNumber <= failTimes ? HttpStatus.SERVICE_UNAVAILABLE.value() : HttpStatus.OK.value();
        return respond("/hooks/flaky", headers, body, status);
    }

    private ResponseEntity<Map<String, Object>> respond(String path, Map<String, String> headers, String body, int status) {
        int effectiveStatus = state.outage() ? HttpStatus.SERVICE_UNAVAILABLE.value() : status;
        String deliveryId = deliveryId(headers);
        boolean duplicate = state.record(path, deliveryId, header(headers, "x-hookguard-event-type"),
                header(headers, "x-hookguard-attempt"), effectiveStatus, body);

        log.info("{} delivery={} attempt={} -> {}{}{}", path, deliveryId, header(headers, "x-hookguard-attempt"),
                effectiveStatus, state.outage() ? " (outage)" : "", duplicate ? " DUPLICATE" : "");

        String message = state.outage() ? "simulated outage"
                : effectiveStatus < 300 ? "received" : "simulated failure";
        return ResponseEntity.status(effectiveStatus).body(Map.of("result", message, "duplicate", duplicate));
    }

    private static String deliveryId(Map<String, String> headers) {
        return header(headers, "x-hookguard-delivery-id");
    }

    private static String header(Map<String, String> headers, String name) {
        // Spring passes header names as received; HTTP header names are case-insensitive.
        return headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
