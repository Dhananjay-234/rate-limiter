package dev.dhananjay.ratelimiter.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import io.jsonwebtoken.security.Keys;

/**
 * Validates the bearer token and pulls the tenant identity out of it.
 *
 * <p>This service does not issue tokens — it only verifies ones minted elsewhere.
 * Identity has to be established before the quota check, otherwise a caller could
 * spend someone else's quota simply by claiming their tenant id in a header.
 */
@Component
public class JwtTenantResolver {

    private static final Logger log = LoggerFactory.getLogger(JwtTenantResolver.class);

    private final SecretKey key;

    public JwtTenantResolver(@Value("${security.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @return the parsed claims, or empty when the token is missing, malformed,
     *         expired, or signed with the wrong key
     */
    public Optional<TenantIdentity> resolve(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }

        String token = authorizationHeader.substring(7);

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String tenantId = claims.get("tenantId", String.class);
            if (tenantId == null || tenantId.isBlank()) {
                return Optional.empty();
            }

            String tier = claims.get("tier", String.class);
            return Optional.of(new TenantIdentity(tenantId, tier));

        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected JWT: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public record TenantIdentity(String tenantId, String tier) {
    }
}
