package dev.dhananjay.ratelimiter.gateway.controller;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Demo token minting endpoint. A real system would never issue its own access
 * tokens at the gateway — that belongs to an identity provider. This exists purely
 * so the project can be exercised with curl straight after {@code docker compose up}.
 */
@RestController
@RequestMapping("/dev/token")
public class TokenController {

    private final SecretKey key;

    public TokenController(@Value("${security.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping
    public Mono<Map<String, Object>> mint(@RequestParam String tenantId,
                                          @RequestParam(defaultValue = "FREE") String tier) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(3600);

        String token = Jwts.builder()
                .subject(tenantId)
                .claim("tenantId", tenantId)
                .claim("tier", tier)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token", token);
        response.put("tenantId", tenantId);
        response.put("tier", tier);
        response.put("expiresAt", expiry.toString());
        return Mono.just(response);
    }
}
