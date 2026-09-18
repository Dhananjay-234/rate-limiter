package dev.dhananjay.ratelimiter.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dhananjay.ratelimiter.core.algorithm.RateLimiterFactory;
import dev.dhananjay.ratelimiter.core.config.RateLimiterProperties;
import dev.dhananjay.ratelimiter.core.metrics.RateLimiterMetrics;
import dev.dhananjay.ratelimiter.core.model.RateLimitResult;
import dev.dhananjay.ratelimiter.core.model.TenantConfig;
import dev.dhananjay.ratelimiter.core.tenant.TenantConfigService;
import dev.dhananjay.ratelimiter.gateway.security.JwtTenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The filter that enforces quotas. Order of operations matters:
 *
 * <ol>
 *   <li>validate the JWT — an unauthenticated caller has no quota to spend</li>
 *   <li>load the tenant's config (cached) — decides limit and algorithm</li>
 *   <li>run the atomic Redis check</li>
 *   <li>either reject with 429 or forward, and attach quota headers either way</li>
 * </ol>
 *
 * Applied to a route via {@code filters: - RateLimiter} in application.yml.
 */
@Component
public class RateLimiterGatewayFilterFactory
        extends AbstractGatewayFilterFactory<RateLimiterGatewayFilterFactory.Config> {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterGatewayFilterFactory.class);

    private final JwtTenantResolver jwtResolver;
    private final TenantConfigService tenantConfigService;
    private final RateLimiterFactory limiterFactory;
    private final RateLimiterMetrics metrics;
    private final RateLimiterProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimiterGatewayFilterFactory(JwtTenantResolver jwtResolver,
                                           TenantConfigService tenantConfigService,
                                           RateLimiterFactory limiterFactory,
                                           RateLimiterMetrics metrics,
                                           RateLimiterProperties properties,
                                           ObjectMapper objectMapper) {
        super(Config.class);
        this.jwtResolver = jwtResolver;
        this.tenantConfigService = tenantConfigService;
        this.limiterFactory = limiterFactory;
        this.metrics = metrics;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {

            if (!properties.isEnabled()) {
                // Baseline mode for benchmarking: measure the gateway without the limiter
                // in the path so the overhead number in the README is honest.
                return chain.filter(exchange);
            }

            String authHeader = exchange.getRequest()
                    .getHeaders()
                    .getFirst(HttpHeaders.AUTHORIZATION);

            Optional<JwtTenantResolver.TenantIdentity> identity = jwtResolver.resolve(authHeader);

            if (identity.isEmpty()) {
                return writeUnauthorized(exchange);
            }

            String tenantId = identity.get().tenantId();
            String route = config.getRouteKey() != null
                    ? config.getRouteKey()
                    : exchange.getRequest().getPath().value();

            long startNanos = System.nanoTime();

            return tenantConfigService.findByTenantId(tenantId)
                    .defaultIfEmpty(TenantConfig.defaultsFor(tenantId))
                    .flatMap(tenantConfig -> limiterFactory
                            .forType(tenantConfig.algorithm())
                            .tryConsume(tenantConfig, route)
                            .flatMap(result -> {

                                metrics.recordRedisLatency(
                                        Duration.ofNanos(System.nanoTime() - startNanos));
                                metrics.recordDecision(tenantId, route,
                                        tenantConfig.algorithm().name(), result.allowed());

                                if (result.degraded()) {
                                    metrics.recordDegraded(tenantId);
                                }

                                if (!result.allowed()) {
                                    return writeTooManyRequests(exchange, tenantConfig, result);
                                }

                                // Quota headers go on the response even when the request is
                                // admitted, so well-behaved clients can self-throttle.
                                exchange.getResponse().beforeCommit(() -> {
                                    applyQuotaHeaders(exchange.getResponse(), result);
                                    return Mono.empty();
                                });

                                return chain.filter(exchange);
                            }))
                    .onErrorResume(error -> {
                        log.error("Rate limit filter failed unexpectedly for tenant {}", tenantId, error);
                        // Never let a bug in the limiter take the gateway down.
                        return properties.isFailOpen()
                                ? chain.filter(exchange)
                                : writeServiceUnavailable(exchange);
                    });
        };
    }

    private void applyQuotaHeaders(ServerHttpResponse response, RateLimitResult result) {
        HttpHeaders headers = response.getHeaders();
        headers.set("X-RateLimit-Limit", String.valueOf(result.limit()));
        headers.set("X-RateLimit-Remaining", String.valueOf(result.remaining()));
        headers.set("X-RateLimit-Reset", String.valueOf(result.resetEpochSeconds()));
        if (result.degraded()) {
            headers.set("X-RateLimit-Degraded", "true");
        }
    }

    private Mono<Void> writeTooManyRequests(ServerWebExchange exchange,
                                            TenantConfig tenantConfig,
                                            RateLimitResult result) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        applyQuotaHeaders(response, result);
        response.getHeaders().set("Retry-After", String.valueOf(result.retryAfterSeconds()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "rate_limit_exceeded");
        body.put("message", "Too many requests");
        body.put("retryAfter", result.retryAfterSeconds());
        body.put("tenantId", tenantConfig.tenantId());
        body.put("tier", tenantConfig.tier().name());

        return writeJson(response, body);
    }

    private Mono<Void> writeUnauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "unauthorized");
        body.put("message", "Missing or invalid bearer token");

        return writeJson(response, body);
    }

    private Mono<Void> writeServiceUnavailable(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "rate_limiter_unavailable");
        body.put("message", "Quota service is unavailable and fail-closed is configured");

        return writeJson(response, body);
    }

    private Mono<Void> writeJson(ServerHttpResponse response, Map<String, Object> body) {
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (Exception ex) {
            bytes = "{\"error\":\"serialization_failed\"}".getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    /** Per-route configuration, set in application.yml. */
    public static class Config {

        /**
         * Logical name used in the Redis key, so limits are per tenant AND per route.
         * Falls back to the raw request path when unset.
         */
        private String routeKey;

        public String getRouteKey() {
            return routeKey;
        }

        public void setRouteKey(String routeKey) {
            this.routeKey = routeKey;
        }
    }
}
