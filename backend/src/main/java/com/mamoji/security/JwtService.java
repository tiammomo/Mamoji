package com.mamoji.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT token utility service.
 *
 * <p>Provides token generation, parsing and validation based on configured secret.
 */
@Service
public class JwtService {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private Long expiration;

    /**
     * Creates HMAC signing key from configured secret.
     */
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generates JWT token carrying user id in subject claim.
     */
    public String generateToken(Long userId) {
        return Jwts.builder()
            .subject(userId.toString())
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + expiration * 1000))
            .signWith(getSigningKey())
            .compact();
    }

    /**
     * Extracts user id from token subject claim.
     */
    public Long extractUserId(String token) {
        return Long.parseLong(Jwts.parser()
            .verifyWith(getSigningKey())
            .build()
            .parseSignedClaims(token)
            .getPayload()
            .getSubject());
    }

    /**
     * Validates token signature and expiration.
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
