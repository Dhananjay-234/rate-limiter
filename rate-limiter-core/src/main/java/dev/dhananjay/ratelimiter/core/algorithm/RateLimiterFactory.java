package dev.dhananjay.ratelimiter.core.algorithm;

import dev.dhananjay.ratelimiter.core.model.AlgorithmType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy pattern dispatch. The tenant's configuration names an algorithm; this
 * factory hands back the matching implementation so the algorithm can be switched
 * per tenant without touching the filter.
 */
public class RateLimiterFactory {

    private final Map<AlgorithmType, RateLimiterStrategy> strategies =
            new EnumMap<>(AlgorithmType.class);

    public RateLimiterFactory(List<RateLimiterStrategy> available) {
        available.forEach(strategy -> strategies.put(strategy.type(), strategy));
    }

    public RateLimiterStrategy forType(AlgorithmType type) {
        RateLimiterStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw new IllegalStateException("No limiter registered for algorithm " + type);
        }
        return strategy;
    }
}
