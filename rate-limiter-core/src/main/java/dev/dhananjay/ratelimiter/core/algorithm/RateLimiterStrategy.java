package dev.dhananjay.ratelimiter.core.algorithm;

import dev.dhananjay.ratelimiter.core.model.AlgorithmType;
import dev.dhananjay.ratelimiter.core.model.RateLimitResult;
import dev.dhananjay.ratelimiter.core.model.TenantConfig;
import reactor.core.publisher.Mono;

/**
 * One rate limiting algorithm. Implementations must be atomic with respect to
 * concurrent callers across multiple gateway instances — in practice that means
 * the whole check-and-decrement happens inside a single Redis Lua script.
 */
public interface RateLimiterStrategy {

    /** Which algorithm this implementation provides; used by the factory to dispatch. */
    AlgorithmType type();

    /**
     * Check and consume one unit of quota.
     *
     * @param config the tenant's effective configuration
     * @param route  logical route key, so limits are per tenant *and* per endpoint
     */
    Mono<RateLimitResult> tryConsume(TenantConfig config, String route);
}
