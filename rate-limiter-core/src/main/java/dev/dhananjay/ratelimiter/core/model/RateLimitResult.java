package dev.dhananjay.ratelimiter.core.model;

/**
 * Outcome of a single rate limit check.
 *
 * @param allowed           whether the request may proceed
 * @param limit             the configured quota for the window
 * @param remaining         tokens/slots left after this check
 * @param resetEpochSeconds epoch second at which the quota fully resets
 * @param retryAfterSeconds seconds the caller should wait (0 when allowed)
 * @param degraded          true when the check could not reach Redis and we failed open
 */
public record RateLimitResult(
        boolean allowed,
        long limit,
        long remaining,
        long resetEpochSeconds,
        long retryAfterSeconds,
        boolean degraded
) {

    public static RateLimitResult allowed(long limit, long remaining, long resetEpochSeconds) {
        return new RateLimitResult(true, limit, remaining, resetEpochSeconds, 0, false);
    }

    public static RateLimitResult rejected(long limit, long resetEpochSeconds, long retryAfterSeconds) {
        return new RateLimitResult(false, limit, 0, resetEpochSeconds, retryAfterSeconds, false);
    }

    /**
     * Fail-open result. Used when Redis is unreachable: we let the request through rather
     * than turning an infrastructure outage into a customer-facing error. See DOC.md for
     * the reasoning behind choosing fail-open over fail-closed.
     */
    public static RateLimitResult failOpen(long limit, long resetEpochSeconds) {
        return new RateLimitResult(true, limit, limit, resetEpochSeconds, 0, true);
    }
}
