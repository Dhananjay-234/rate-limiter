package dev.dhananjay.ratelimiter.core.model;

/**
 * Effective rate limiting configuration for one tenant.
 *
 * @param tenantId    stable tenant identifier, taken from the JWT
 * @param tier        subscription tier, supplies the default limit
 * @param algorithm   which limiting algorithm to apply
 * @param customLimit optional override; when greater than zero it wins over the tier default
 */
public record TenantConfig(
        String tenantId,
        Tier tier,
        AlgorithmType algorithm,
        long customLimit
) {

    /** Unknown tenants fall back to the safest tier rather than failing the request. */
    public static TenantConfig defaultsFor(String tenantId) {
        return new TenantConfig(tenantId, Tier.FREE, AlgorithmType.TOKEN_BUCKET, 0);
    }

    /** Requests allowed per minute after applying any override. */
    public long effectiveLimit() {
        return customLimit > 0 ? customLimit : tier.requestsPerMinute();
    }
}
