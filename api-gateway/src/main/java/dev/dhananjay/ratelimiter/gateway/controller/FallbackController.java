package dev.dhananjay.ratelimiter.gateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Served when Resilience4j opens a circuit. Returning a fast, structured 503 is
 * the whole point of the circuit breaker: a failing downstream stops consuming
 * gateway threads and connections waiting for timeouts it will never satisfy.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @GetMapping("/{service}")
    public Mono<ResponseEntity<Map<String, Object>>> fallback(@PathVariable String service) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "service_unavailable");
        body.put("message", "Downstream service is not responding; circuit is open");
        body.put("service", service);

        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body));
    }
}
