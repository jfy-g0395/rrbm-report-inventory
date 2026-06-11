package rrbm_backend;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);
    private static final long EXPIRATION_TIME = 8 * 60 * 60 * 1000; // 8 hours

    /** The committed dev fallback from application.properties — must never be used in prod. */
    private static final String DEV_FALLBACK_SECRET =
        "rrbm-secret-key-minimum-256-bits-for-hs256-algorithm-security";

    private final SecretKey key;

    /**
     * Secret injected from rrbm.jwt.secret (env var RRBM_JWT_SECRET, with dev fallback).
     *
     * M-2.2: if the committed dev fallback is in use, log a loud warning. When
     * rrbm.security.fail-on-default-jwt-secret=true (set this in production) the app
     * refuses to start rather than signing tokens with a publicly-known key.
     */
    public JwtUtil(@Value("${rrbm.jwt.secret}") String secret,
                   @Value("${rrbm.security.fail-on-default-jwt-secret:false}") boolean failOnDefault) {
        if (DEV_FALLBACK_SECRET.equals(secret)) {
            if (failOnDefault)
                throw new IllegalStateException(
                    "RRBM_JWT_SECRET is not set — refusing to start with the committed dev JWT secret. "
                    + "Generate one with `openssl rand -hex 32` and set RRBM_JWT_SECRET.");
            log.warn("⚠ JWT is using the committed DEV fallback secret. "
                   + "Set RRBM_JWT_SECRET to a unique value before deploying (M-2.2).");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * Generate JWT token for a user
     */
    public String generateToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId());
        claims.put("email", user.getEmail());
        claims.put("role", user.getRole());
        
        return Jwts.builder()
                .claims(claims)
                .subject(user.getEmail())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME))
                .signWith(key)
                .compact();
    }
    
    /**
     * Extract email from token
     */
    public String extractEmail(String token) {
        return extractClaims(token).getSubject();
    }

    /**
     * Alias for extractEmail — returns the user's email (JWT subject).
     * Provided for API consistency; the JWT stores email, not a separate username.
     */
    public String extractUsername(String token) {
        return extractEmail(token);
    }

    /**
     * Extract role claim from token
     */
    public String extractRole(String token) {
        Object role = extractClaims(token).get("role");
        return role != null ? role.toString() : null;
    }

    /**
     * Extract userId claim from token
     */
    public Long extractUserId(String token) {
        Object id = extractClaims(token).get("userId");
        if (id instanceof Number) return ((Number) id).longValue();
        return null;
    }
    
    /**
     * Validate token
     */
    public boolean validateToken(String token, String email) {
        try {
            String extractedEmail = extractEmail(token);
            return extractedEmail.equals(email) && !isTokenExpired(token);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Extract all claims from token
     */
    private Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
    
    /**
     * Check if token is expired
     */
    private boolean isTokenExpired(String token) {
        return extractClaims(token).getExpiration().before(new Date());
    }
}