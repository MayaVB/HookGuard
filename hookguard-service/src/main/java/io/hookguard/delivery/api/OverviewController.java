package io.hookguard.delivery.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnBooleanProperty(name = "hookguard.api.enabled", matchIfMissing = true)
public class OverviewController {

    private final OverviewService overviewService;

    public OverviewController(OverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping("/api/overview")
    public OverviewResponse overview() {
        return overviewService.snapshot();
    }
}
