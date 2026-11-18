package com.smartflow.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;

public final class JwtUtil {

    private JwtUtil() {
    }

    private static SecretKey key(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public static String generate(String secret, long ttlMinutes, Long uid,
                                  List<String> roles, Long deptId, String username) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + ttlMinutes * 60_000L);
        return Jwts.builder()
                .claims().add(Map.of(
                        "uid", uid,
                        "roles", roles == null ? List.of() : roles,
                        "deptId", deptId == null ? 0L : deptId,
                        "username", username == null ? "" : username
                )).and()
                .issuedAt(now)
                .expiration(exp)
                .signWith(key(secret))
                .compact();
    }

    public static Claims parse(String secret, String token) {
        return Jwts.parser()
                .verifyWith(key(secret))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
