package com.pause.backend;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HealthController exposes a simple health-check endpoint.
 *
 * Spring concept: @RestController tells Spring "this class handles HTTP requests
 * and its methods return data directly as the response body" (not an HTML page).
 */
@RestController
public class HealthController {

    /**
     * Spring concept: @GetMapping("/health") tells Spring "when a GET request
     * arrives at the path /health, call this method and send back whatever it returns".
     */
    @GetMapping("/health")
    public String health() {
        return "Pause is running";
    }
}
