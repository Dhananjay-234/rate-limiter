package dev.dhananjay.ratelimiter.core.tenant;

import dev.dhananjay.ratelimiter.core.model.TenantConfig;
import reactor.core.publisher.Mono;

/**
 * Resolves a tenant's effective limiter configuration. Implementations are expected
 * to cache aggressively — this sits on the hot path of every single request.
 */
public interface TenantConfigService {

    Mono<TenantConfig> findByTenantId(String tenantId);

    /** Drop the cached copy so the next request re-reads the source of truth. */
    Mono<Void> evict(String tenantId);
}
