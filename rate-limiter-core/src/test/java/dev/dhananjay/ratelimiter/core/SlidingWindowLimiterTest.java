package dev.dhananjay.ratelimiter.core;

import dev.dhananjay.ratelimiter.core.algorithm.SlidingWindowLimiter;
import dev.dhananjay.ratelimiter.core.config.RateLimiterProperties;
import dev.dhananjay.ratelimiter.core.model.AlgorithmType;
import dev.dhananjay.ratelimiter.core.model.TenantConfig;
import dev.dhananjay.ratelimiter.core.model.Tier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same correctness bar as the token bucket, but the sliding window admits no burst
 * at all, so the admitted count should be exactly the limit with no tolerance.
 */
@Testcontainers
@DisplayName("Sliding window enforces the limit exactly, with no burst allowance")
class SlidingWindowLimiterTest {

    private static final int LIMIT = 50;
    private static final int CONCURRENT_CALLERS = 400;

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    private SlidingWindowLimiter limiter;
    private TenantConfig config;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();

        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/sliding_window.lua"));
        script.setResultType(List.class);

        limiter = new SlidingWindowLimiter(
                new ReactiveStringRedisTemplate(factory), script, new RateLimiterProperties());

        config = new TenantConfig(
                "tenant-" + UUID.randomUUID(), Tier.FREE, AlgorithmType.SLIDING_WINDOW, LIMIT);
    }

    @RepeatedTest(5)
    @DisplayName("400 parallel callers against a limit of 50 admit exactly 50")
    void admitsExactlyLimit() {
        AtomicInteger admitted = new AtomicInteger();

        Flux.range(0, CONCURRENT_CALLERS)
                .parallel(64)
                .runOn(Schedulers.boundedElastic())
                .flatMap(i -> limiter.tryConsume(config, "sw-route"))
                .doOnNext(result -> {
                    if (result.allowed()) {
                        admitted.incrementAndGet();
                    }
                })
                .sequential()
                .blockLast();

        // No refill mechanic inside the window, so this is exact — not a range.
        assertThat(admitted.get()).isEqualTo(LIMIT);
    }
}
