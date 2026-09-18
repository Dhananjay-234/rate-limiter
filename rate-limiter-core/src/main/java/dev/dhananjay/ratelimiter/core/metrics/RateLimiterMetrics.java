package dev.dhananjay.ratelimiter.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * Micrometer instrumentation. Everything here is scraped by Prometheus from
 * /actuator/prometheus and rendered by the bundled Grafana dashboard.
 */
public class RateLimiterMetrics {

    private final MeterRegistry registry;
    private final Timer redisLatency;

    public RateLimiterMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.redisLatency = Timer.builder("rate_limiter_redis_latency")
                .description("Time spent executing the rate limit Lua script in Redis")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
    }

    public void recordDecision(String tenantId, String route, String algorithm, boolean allowed) {
        Counter.builder("rate_limiter_requests_total")
                .description("Rate limit decisions by outcome")
                .tag("tenant", tenantId)
                .tag("route", route)
                .tag("algorithm", algorithm)
                .tag("result", allowed ? "allowed" : "rejected")
                .register(registry)
                .increment();
    }

    /** Incremented whenever we admitted a request because Redis was unreachable. */
    public void recordDegraded(String tenantId) {
        Counter.builder("rate_limiter_fail_open_total")
                .description("Requests admitted without a quota check because Redis was unavailable")
                .tag("tenant", tenantId)
                .register(registry)
                .increment();
    }

    public void recordRedisLatency(Duration duration) {
        redisLatency.record(duration);
    }
}
