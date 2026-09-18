package dev.dhananjay.ratelimiter.core.algorithm;

import dev.dhananjay.ratelimiter.core.config.RateLimiterProperties;
import dev.dhananjay.ratelimiter.core.model.AlgorithmType;
import dev.dhananjay.ratelimiter.core.model.RateLimitResult;
import dev.dhananjay.ratelimiter.core.model.TenantConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sliding window counter, backed by a Redis sorted set of request timestamps.
 *
 * <p>Characteristics: no burst allowance — the quota is enforced over a continuously
 * moving window, which produces smoother downstream traffic than the token bucket.
 * The trade-off is memory: one sorted-set member per request currently in the window.
 */
public class SlidingWindowLimiter implements RateLimiterStrategy {

    private static final Logger log = LoggerFactory.getLogger(SlidingWindowLimiter.class);
    private static final String KEY_PREFIX = "rate_limit:sw:";
    private static final long WINDOW_MILLIS = 60_000L;

    private final ReactiveStringRedisTemplate redis;
    private final RedisScript<List> script;
    private final RateLimiterProperties properties;

    public SlidingWindowLimiter(ReactiveStringRedisTemplate redis,
                                RedisScript<List> script,
                                RateLimiterProperties properties) {
        this.redis = redis;
        this.script = script;
        this.properties = properties;
    }

    @Override
    public AlgorithmType type() {
        return AlgorithmType.SLIDING_WINDOW;
    }

    @Override
    public Mono<RateLimitResult> tryConsume(TenantConfig config, String route) {
        long limit = config.effectiveLimit();
        long nowMillis = Instant.now().toEpochMilli();
        String key = KEY_PREFIX + config.tenantId() + ":" + route;

        // Unique member id: two requests in the same millisecond must not collide
        // into a single sorted-set entry, which would under-count usage.
        String memberId = nowMillis + "-" + UUID.randomUUID();

        return redis.execute(
                        script,
                        List.of(key),
                        List.of(
                                String.valueOf(limit),
                                String.valueOf(WINDOW_MILLIS),
                                String.valueOf(nowMillis),
                                memberId,
                                String.valueOf(properties.getKeyTtlSeconds())
                        ))
                .next()
                .map(raw -> toResult(raw, limit))
                .onErrorResume(error -> {
                    log.warn("Redis unavailable for sliding window check on key {} — failing open: {}",
                            key, error.toString());
                    return Mono.just(RateLimitResult.failOpen(
                            limit, Instant.now().getEpochSecond() + 60));
                });
    }

    @SuppressWarnings("unchecked")
    private RateLimitResult toResult(Object raw, long limit) {
        List<Long> values = (List<Long>) raw;
        boolean allowed = values.get(0) == 1L;
        long remaining = values.get(1);
        long resetEpoch = values.get(2);
        long retryAfter = values.get(3);

        return allowed
                ? RateLimitResult.allowed(limit, remaining, resetEpoch)
                : RateLimitResult.rejected(limit, resetEpoch, retryAfter);
    }
}
