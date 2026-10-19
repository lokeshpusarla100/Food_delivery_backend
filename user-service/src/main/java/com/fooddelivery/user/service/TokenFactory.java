package com.fooddelivery.user.service;

import org.springframework.stereotype.Component;
import java.security.*;
import java.util.Base64;

@Component
public class TokenFactory {
    private final SecureRandom random = new SecureRandom();
    public String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public String hash(String raw) {
        if(raw == null || raw.length() > 512) throw new IllegalArgumentException("Invalid token");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable",ex);
        }
    }
}
