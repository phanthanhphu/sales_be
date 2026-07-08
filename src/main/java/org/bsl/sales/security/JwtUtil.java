package org.bsl.sales.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.bsl.sales.model.User;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

@Component
public class JwtUtil {
    private final String jwtSecret = "VerySecretKeyThatIsAtLeast256bitsLongForHS256Algorithm!!!";
    private final int jwtExpirationMs = 24 * 60 * 60 * 1000;
    private final Key key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));

    public String generateToken(String email, String role, long tokenVersion, Collection<String> accessPermissions) {
        String normalizedRole = User.normalizeRole(role);
        List<String> normalizedPermissions = User.normalizeAccessPermissions(accessPermissions, User.ROLE_ADMIN.equals(normalizedRole));

        return Jwts.builder()
                .setSubject(email)
                .claim("role", normalizedRole)
                .claim("tokenVersion", tokenVersion)
                .claim("accessPermissions", normalizedPermissions)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + jwtExpirationMs))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public String generateToken(String email, String role, long tokenVersion) {
        return generateToken(email, role, tokenVersion, List.of(User.ACCESS_VIEW_SYSTEM));
    }

    public String generateToken(String email, String role) {
        return generateToken(email, role, 1L, List.of(User.ACCESS_VIEW_SYSTEM));
    }

    public boolean validateToken(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException exception) {
            return false;
        }
    }

    public boolean validateToken(String token, String email, long tokenVersion) {
        try {
            Claims claims = parse(token).getBody();
            Long storedVersion = claims.get("tokenVersion", Long.class);
            return email != null
                    && email.equals(claims.getSubject())
                    && storedVersion != null
                    && storedVersion == tokenVersion
                    && !claims.getExpiration().before(new Date());
        } catch (JwtException | IllegalArgumentException exception) {
            return false;
        }
    }

    public String getEmailFromToken(String token) {
        try { return parse(token).getBody().getSubject(); }
        catch (JwtException | IllegalArgumentException exception) { return null; }
    }

    public String getRoleFromToken(String token) {
        try { return parse(token).getBody().get("role", String.class); }
        catch (JwtException | IllegalArgumentException exception) { return null; }
    }

    public long getTokenVersionFromToken(String token) {
        try {
            Long value = parse(token).getBody().get("tokenVersion", Long.class);
            return value == null ? -1L : value;
        } catch (JwtException | IllegalArgumentException exception) {
            return -1L;
        }
    }

    @SuppressWarnings("unchecked")
    public List<String> getAccessPermissionsFromToken(String token) {
        try {
            Object raw = parse(token).getBody().get("accessPermissions");
            if (raw instanceof Collection<?> collection) {
                List<String> values = new ArrayList<>();
                for (Object value : collection) values.add(String.valueOf(value));
                return values;
            }
            return List.of();
        } catch (JwtException | IllegalArgumentException exception) {
            return List.of();
        }
    }

    public boolean isTokenBlacklisted(String token) { return false; }
    public boolean isTokenExpired(String token) {
        try { return parse(token).getBody().getExpiration().before(new Date()); }
        catch (Exception exception) { return true; }
    }

    private Jws<Claims> parse(String token) {
        return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
    }
}
