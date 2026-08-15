package com.pulsechat.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {
    private final SecretKey key;
    public JwtService(@Value("${app.jwt-secret}") String secret) {
        if (secret == null || secret.length() < 32) throw new IllegalStateException("JWT_SECRET must be at least 32 characters");
        key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
    public String create(String userId, String role, String name) {
        Instant now = Instant.now();
        return Jwts.builder().subject(userId).claim("role", role).claim("name", name)
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(60L*60*24*7)))
                .signWith(key).compact();
    }
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
