package org.bsl.sales.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.bsl.sales.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

@Component
public class JwtService {
    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expiration-ms}")
    private long expirationMs;

    private Key signingKey;

    @PostConstruct
    void initialize() {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
        }
        signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(User user) {
        Date now = new Date();
        Date expiresAt = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .setSubject(user.getEmail())
                .claim("role", user.getRole())
                .claim("tokenVersion", user.getTokenVersion())
                .setIssuedAt(now)
                .setExpiration(expiresAt)
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public JwtPayload parse(String token) {
        Jws<Claims> parsed = Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token);
        Claims claims = parsed.getBody();
        Object version = claims.get("tokenVersion");
        long tokenVersion = version instanceof Number number ? number.longValue() : 0L;
        return new JwtPayload(claims.getSubject(), claims.get("role", String.class), tokenVersion);
    }

    public long getExpirationSeconds() {
        return expirationMs / 1000;
    }
}
