package dev.dhananjay.ratelimiter.serviceb;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Trivial downstream. The /chaos endpoints exist so the circuit breaker can be
 * demonstrated without killing the container — flip the switch, watch the breaker
 * open in Grafana, flip it back, watch it close.
 */
@RestController
public class DemoController {

    private final AtomicBoolean failing = new AtomicBoolean(false);

    @GetMapping("/ping")
    public Mono<Map<String, Object>> ping() {
        if (failing.get()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Chaos mode enabled"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "service-b");
        body.put("status", "ok");
        body.put("timestamp", Instant.now().toString());
        return Mono.just(body);
    }

    /** Simulates a slow downstream so the 2s time limiter can be exercised. */
    @GetMapping("/data")
    public Mono<Map<String, Object>> data(@RequestParam(defaultValue = "0") long delayMs) {
        if (failing.get()) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Chaos mode enabled"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "service-b");
        body.put("payload", "some data from service B");
        body.put("delayMs", delayMs);

        return Mono.just(body).delayElement(Duration.ofMillis(delayMs));
    }

    @GetMapping("/chaos/on")
    public Mono<String> chaosOn() {
        failing.set(true);
        return Mono.just("service-b now failing");
    }

    @GetMapping("/chaos/off")
    public Mono<String> chaosOff() {
        failing.set(false);
        return Mono.just("service-b recovered");
    }
}
