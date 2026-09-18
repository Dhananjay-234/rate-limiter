package dev.dhananjay.ratelimiter.gateway.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dhananjay.ratelimiter.core.config.RateLimiterProperties;
import dev.dhananjay.ratelimiter.core.model.AlgorithmType;
import dev.dhananjay.ratelimiter.core.model.TenantConfig;
import dev.dhananjay.ratelimiter.core.model.Tier;
import dev.dhananjay.ratelimiter.core.tenant.TenantConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Two-level lookup for tenant configuration.
 *
 * <p>Postgres is the source of truth but is far too slow to hit on every request, so
 * resolved configs are cached in Redis with a short TTL. The TTL is deliberately short
 * (60s by default) — it bounds how long a tier change takes to propagate without
 * requiring cache invalidation across every gateway instance.
 */
@Service
public class CachingTenantConfigService implements TenantConfigService {

    private static final Logger log = LoggerFactory.getLogger(CachingTenantConfigService.class);
    private static final String CACHE_PREFIX = "tenant_config:";

    private final TenantRepository repository;
    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration cacheTtl;

    public CachingTenantConfigService(TenantRepository repository,
                                      ReactiveStringRedisTemplate redis,
                                      ObjectMapper objectMapper,
                                      RateLimiterProperties properties) {
        this.repository = repository;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.cacheTtl = Duration.ofSeconds(properties.getTenantCacheTtlSeconds());
    }

    @Override
    public Mono<TenantConfig> findByTenantId(String tenantId) {
        String cacheKey = CACHE_PREFIX + tenantId;

        return redis.opsForValue().get(cacheKey)
                .flatMap(this::deserialize)
                .switchIfEmpty(loadAndCache(tenantId, cacheKey))
                // A cache or database problem must not fail the request: an unknown tenant
                // gets FREE tier limits, which is the safe direction to be wrong in.
                .onErrorResume(error -> {
                    log.warn("Tenant config lookup failed for {}, applying defaults: {}",
                            tenantId, error.toString());
                    return Mono.just(TenantConfig.defaultsFor(tenantId));
                });
    }

    @Override
    public Mono<Void> evict(String tenantId) {
        return redis.opsForValue().delete(CACHE_PREFIX + tenantId).then();
    }

    private Mono<TenantConfig> loadAndCache(String tenantId, String cacheKey) {
        return repository.findById(tenantId)
                .map(this::toConfig)
                .defaultIfEmpty(TenantConfig.defaultsFor(tenantId))
                .flatMap(config -> serialize(config)
                        .flatMap(json -> redis.opsForValue().set(cacheKey, json, cacheTtl))
                        .thenReturn(config));
    }

    private TenantConfig toConfig(TenantEntity entity) {
        return new TenantConfig(
                entity.getTenantId(),
                Tier.fromString(entity.getTier()),
                AlgorithmType.fromString(entity.getAlgorithm()),
                entity.getCustomLimit() == null ? 0 : entity.getCustomLimit()
        );
    }

    private Mono<TenantConfig> deserialize(String json) {
        return Mono.fromCallable(() -> objectMapper.readValue(json, TenantConfig.class))
                .onErrorResume(error -> Mono.empty());
    }

    private Mono<String> serialize(TenantConfig config) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(config))
                .onErrorResume(error -> Mono.empty());
    }
}
