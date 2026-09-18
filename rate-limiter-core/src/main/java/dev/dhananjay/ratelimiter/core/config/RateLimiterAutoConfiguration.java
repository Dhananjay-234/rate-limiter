package dev.dhananjay.ratelimiter.core.config;

import dev.dhananjay.ratelimiter.core.algorithm.RateLimiterFactory;
import dev.dhananjay.ratelimiter.core.algorithm.RateLimiterStrategy;
import dev.dhananjay.ratelimiter.core.algorithm.SlidingWindowLimiter;
import dev.dhananjay.ratelimiter.core.algorithm.TokenBucketLimiter;
import dev.dhananjay.ratelimiter.core.metrics.RateLimiterMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Wires the limiter. Both Lua scripts are loaded once at startup; Spring Data Redis
 * caches their SHA and issues EVALSHA on every call, so the script body is not sent
 * over the wire per request.
 */
@Configuration
@EnableConfigurationProperties(RateLimiterProperties.class)
public class RateLimiterAutoConfiguration {

    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> tokenBucketScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        script.setResultType(List.class);
        return script;
    }

    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> slidingWindowScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/sliding_window.lua"));
        script.setResultType(List.class);
        return script;
    }

    @Bean
    @SuppressWarnings("rawtypes")
    public TokenBucketLimiter tokenBucketLimiter(ReactiveStringRedisTemplate redis,
                                                 RedisScript<List> tokenBucketScript,
                                                 RateLimiterProperties properties) {
        return new TokenBucketLimiter(redis, tokenBucketScript, properties);
    }

    @Bean
    @SuppressWarnings("rawtypes")
    public SlidingWindowLimiter slidingWindowLimiter(ReactiveStringRedisTemplate redis,
                                                     RedisScript<List> slidingWindowScript,
                                                     RateLimiterProperties properties) {
        return new SlidingWindowLimiter(redis, slidingWindowScript, properties);
    }

    @Bean
    public RateLimiterFactory rateLimiterFactory(List<RateLimiterStrategy> strategies) {
        return new RateLimiterFactory(strategies);
    }

    @Bean
    public RateLimiterMetrics rateLimiterMetrics(MeterRegistry registry) {
        return new RateLimiterMetrics(registry);
    }
}
