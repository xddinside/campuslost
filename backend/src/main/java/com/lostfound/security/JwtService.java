package com.lostfound.security;

import com.lostfound.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

@Service
public class JwtService {

    private static final String LEGACY_DEMO_SECRET =
            "c2VjcmV0LWtleS1sb3N0LWFuZC1mb3VuZC1kZW1vLTIwMjYtc2VjcmV0LWtleS1sb3N0LWFuZC1mb3VuZC1kZW1vLTIwMjY=";

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-ms}") long expirationMs) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET is not configured. Run scripts/bootstrap-local.sh to generate local secrets.");
        }
        if (LEGACY_DEMO_SECRET.equals(secret.trim())) {
            throw new IllegalStateException(
                    "The retired demo JWT key cannot be used. Generate a new local secret.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("JWT_SECRET must be Base64-encoded.", ex);
        }
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must decode to at least 32 bytes (256 bits).");
        }
        if (expirationMs <= 0) {
            throw new IllegalStateException("JWT expiration must be positive.");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expirationMs = expirationMs;
    }

    public String generate(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("name", user.getName())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(key)
                .compact();
    }

    public Long parseUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Long.valueOf(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
