package dev.dhananjay.ratelimiter.core.model;

/**
 * Subscription tiers. Limits are expressed per minute and translated into a
 * per-second refill rate by the token bucket algorithm.
 */
public enum Tier {

    FREE(60),
    PRO(300),
    ENTERPRISE(1000);

    private final long requestsPerMinute;

    Tier(long requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    public long requestsPerMinute() {
        return requestsPerMinute;
    }

    /** Lenient parse: an unknown or missing tier degrades to FREE rather than throwing. */
    public static Tier fromString(String value) {
        if (value == null || value.isBlank()) {
            return FREE;
        }
        try {
            return Tier.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return FREE;
        }
    }
}
