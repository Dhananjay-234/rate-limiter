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

/**
 * Token bucket. Each tenant/route pair owns a bucket that refills at a steady rate.
 *
 * <p>Characteristics: allows short bursts up to bucket capacity, needs only two hash
 * fields per key regardless of traffic volume, and costs a single Redis round trip.
 * This is the default algorithm.
 */
public class TokenBucketLimiter implements RateLimiterStrategy {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketLimiter.class);
    private static final String KEY_PREFIX = "rate_limit:tb:";

    private final ReactiveStringRedisTemplate redis;
    private final RedisScript<List> script;
    private final RateLimiterProperties properties;

    public TokenBucketLimiter(ReactiveStringRedisTemplate redis,
                              RedisScript<List> script,
                              RateLimiterProperties properties) {
        this.redis = redis;
        this.script = script;
        this.properties = properties;
    }

    @Override
    public AlgorithmType type() {
        return AlgorithmType.TOKEN_BUCKET;
    }

    @Override
    public Mono<RateLimitResult> tryConsume(TenantConfig config, String route) {
        long capacity = config.effectiveLimit();
        double refillPerSecond = capacity / 60.0;
        long nowMillis = Instant.now().toEpochMilli();
        String key = KEY_PREFIX + config.tenantId() + ":" + route;

        return redis.execute(
                        script,
                        List.of(key),
                        List.of(
                                String.valueOf(capacity),
                                String.valueOf(refillPerSecond),
                                String.valueOf(nowMillis),
                                String.valueOf(properties.getKeyTtlSeconds())
                        ))
                .next()
                .map(raw -> toResult(raw, capacity))
                .onErrorResume(error -> {
                    // Redis is unreachable. Fail open: an infrastructure problem should not
                    // become a 5xx for the customer. The degraded flag is surfaced as a metric
                    // so this never passes silently.
                    log.warn("Redis unavailable for token bucket check on key {} — failing open: {}",
                            key, error.toString());
                    return Mono.just(RateLimitResult.failOpen(
                            capacity, Instant.now().getEpochSecond() + 60));
                });
    }

    @SuppressWarnings("unchecked")
    private RateLimitResult toResult(Object raw, long capacity) {
        List<Long> values = (List<Long>) raw;
        boolean allowed = values.get(0) == 1L;
        long remaining = values.get(1);
        long resetEpoch = values.get(2);
        long retryAfter = values.get(3);

        return allowed
                ? RateLimitResult.allowed(capacity, remaining, resetEpoch)
                : RateLimitResult.rejected(capacity, resetEpoch, retryAfter);
    }
}
