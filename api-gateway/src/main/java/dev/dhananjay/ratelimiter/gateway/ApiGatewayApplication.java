package dev.dhananjay.ratelimiter.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import dev.dhananjay.ratelimiter.core.config.RateLimiterAutoConfiguration;

@SpringBootApplication
@Import(RateLimiterAutoConfiguration.class)
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
