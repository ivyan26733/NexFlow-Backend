package com.nexflow.nexflow_backend.security;

import com.nexflow.nexflow_backend.model.domain.NexUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
public class JwtTokenProvider {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiry-ms:604800000}")
    private long jwtExpiryMs;

    @Value("${app.local-dev:false}")
    private boolean localDev;

    /** Fail fast on startup if the secret is too weak or still the default placeholder. */
    @PostConstruct
    public void validateSecret() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "FATAL: app.jwt.secret is not set. Add JWT_SECRET to your environment.");
        }
        if (!localDev && jwtSecret.contains("local-dev")) {
            throw new IllegalStateException(
                    "FATAL: app.jwt.secret still uses the local-dev placeholder in production. " +
                    "Set JWT_SECRET env var to a strong random value (openssl rand -base64 64).");
        }
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "FATAL: app.jwt.secret must be at least 32 bytes. Current length: " + keyBytes.length);
        }
        log.info("[JWT] Secret validated OK (length={})", keyBytes.length);
    }

    public String generateToken(NexUser user) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + jwtExpiryMs);

        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role",  user.getRole().name())
                .claim("name",  user.getName())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSigningKey())
                .compact();
    }

    public Claims validateToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public UUID getUserId(String token) {
        return UUID.fromString(validateToken(token).getSubject());
    }

    public boolean isValid(String token) {
        try {
            validateToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        // PostConstruct already guarantees >= 32 bytes; no padding needed
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
