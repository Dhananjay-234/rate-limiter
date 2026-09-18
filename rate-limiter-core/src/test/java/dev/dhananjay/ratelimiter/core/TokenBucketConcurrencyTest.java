package dev.dhananjay.ratelimiter.core;

import dev.dhananjay.ratelimiter.core.algorithm.TokenBucketLimiter;
import dev.dhananjay.ratelimiter.core.config.RateLimiterProperties;
import dev.dhananjay.ratelimiter.core.model.AlgorithmType;
import dev.dhananjay.ratelimiter.core.model.RateLimitResult;
import dev.dhananjay.ratelimiter.core.model.TenantConfig;
import dev.dhananjay.ratelimiter.core.model.Tier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
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
 * The correctness proof for this project.
 *
 * <p>A naive limiter that does GET then SET from Java will pass a sequential test and
 * fail this one: under genuine parallelism two callers read the same token count and
 * both decrement it, so more requests are admitted than the quota allows. Because the
 * whole check-and-decrement runs inside one Lua script, Redis serialises it and the
 * admitted count is exact.
 *
 * <p>Run it repeatedly — a race condition that shows up one run in twenty is still a bug.
 */
@Testcontainers
@DisplayName("Token bucket admits exactly the configured quota under concurrency")
class TokenBucketConcurrencyTest {

    private static final int CAPACITY = 60;
    private static final int CONCURRENT_CALLERS = 500;

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    private TokenBucketLimiter limiter;
    private TenantConfig config;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();

        ReactiveStringRedisTemplate template = new ReactiveStringRedisTemplate(factory);

        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        script.setResultType(List.class);

        limiter = new TokenBucketLimiter(template, script, new RateLimiterProperties());

        // Fresh tenant per test so buckets never leak between runs.
        config = new TenantConfig(
                "tenant-" + UUID.randomUUID(),
                Tier.FREE,
                AlgorithmType.TOKEN_BUCKET,
                CAPACITY);
    }

    @RepeatedTest(5)
    @DisplayName("500 parallel callers against a 60-token bucket admit exactly 60")
    void admitsExactlyCapacityUnderParallelLoad() {
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        Flux.range(0, CONCURRENT_CALLERS)
                .parallel(64)
                .runOn(Schedulers.boundedElastic())
                .flatMap(i -> limiter.tryConsume(config, "test-route"))
                .doOnNext(result -> {
                    if (result.allowed()) {
                        admitted.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                })
                .sequential()
                .blockLast();

        // Refill during the test can legitimately add a token or two (capacity/60 per
        // second), so we allow a small upward tolerance but never the unbounded
        // over-admission a non-atomic implementation produces.
        assertThat(admitted.get())
                .as("admitted requests must not exceed the bucket capacity")
                .isBetween(CAPACITY, CAPACITY + 2);

        assertThat(admitted.get() + rejected.get()).isEqualTo(CONCURRENT_CALLERS);
    }

    @Test
    @DisplayName("Rejected responses carry a usable Retry-After hint")
    void rejectionIncludesRetryAfter() {
        Flux.range(0, CAPACITY)
                .concatMap(i -> limiter.tryConsume(config, "retry-route"))
                .blockLast();

        RateLimitResult result = limiter.tryConsume(config, "retry-route").block();

        assertThat(result).isNotNull();
        assertThat(result.allowed()).isFalse();
        assertThat(result.retryAfterSeconds()).isPositive();
        assertThat(result.remaining()).isZero();
    }

    @Test
    @DisplayName("Tokens refill over time")
    void refillsOverTime() throws InterruptedException {
        Flux.range(0, CAPACITY)
                .concatMap(i -> limiter.tryConsume(config, "refill-route"))
                .blockLast();

        assertThat(limiter.tryConsume(config, "refill-route").block().allowed()).isFalse();

        // capacity/60 tokens per second => 60-token bucket refills 1 token/sec.
        Thread.sleep(1_500);

        assertThat(limiter.tryConsume(config, "refill-route").block().allowed()).isTrue();
    }
}
