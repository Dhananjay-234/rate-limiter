package dev.dhananjay.ratelimiter.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the limiter, bound from {@code ratelimiter.*} in application.yml.
 */
@ConfigurationProperties(prefix = "ratelimiter")
public class RateLimiterProperties {

    /** Master switch — useful for running the baseline half of the benchmark. */
    private boolean enabled = true;

    /**
     * When Redis is unreachable: true admits the request (availability first),
     * false rejects it with 503 (correctness first). See DOC.md section 8.
     */
    private boolean failOpen = true;

    /** Idle keys are evicted after this many seconds so Redis memory stays bounded. */
    private long keyTtlSeconds = 3600;

    /** How long a tenant's config stays cached in Redis before we re-read Postgres. */
    private long tenantCacheTtlSeconds = 60;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFailOpen() {
        return failOpen;
    }

    public void setFailOpen(boolean failOpen) {
        this.failOpen = failOpen;
    }

    public long getKeyTtlSeconds() {
        return keyTtlSeconds;
    }

    public void setKeyTtlSeconds(long keyTtlSeconds) {
        this.keyTtlSeconds = keyTtlSeconds;
    }

    public long getTenantCacheTtlSeconds() {
        return tenantCacheTtlSeconds;
    }

    public void setTenantCacheTtlSeconds(long tenantCacheTtlSeconds) {
        this.tenantCacheTtlSeconds = tenantCacheTtlSeconds;
    }
}
