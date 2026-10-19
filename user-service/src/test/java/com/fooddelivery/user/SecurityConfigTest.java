package com.fooddelivery.user;

import com.fooddelivery.user.config.SecurityConfig;
import com.fooddelivery.user.entity.User;
import com.fooddelivery.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecurityConfigTest {
    private final SecurityConfig config=new SecurityConfig();
    @Test void rejectsWeakSigningSecret() {
        assertThrows(IllegalStateException.class,()->config.signingKey("too-short"));
        assertNotNull(config.signingKey("a-strong-key-with-at-least-32-characters"));
    }
    @Test void rejectsStaleTokenVersion() {
        UserRepository users=mock(UserRepository.class);
        when(users.findById("u-1")).thenReturn(Optional.of(
            User.builder().userId("u-1").isActive(true).tokenVersion(2).build()));
        Jwt jwt=new Jwt("token",Instant.now(),Instant.now().plusSeconds(120),
            Map.of("alg","HS256"),Map.of("sub","u-1","ver",1));
        assertThrows(BadCredentialsException.class,
            ()->config.jwtAuthenticationConverter(users).convert(jwt));
    }
    @Test void authenticatesActiveUser() {
        UserRepository users=mock(UserRepository.class);
        when(users.findById("u-1")).thenReturn(Optional.of(
            User.builder().userId("u-1").isActive(true).tokenVersion(2).build()));
        Jwt jwt=new Jwt("token",Instant.now(),Instant.now().plusSeconds(120),
            Map.of("alg","HS256"),Map.of("sub","u-1","ver",2));
        var result=config.jwtAuthenticationConverter(users).convert(jwt);
        assertEquals("u-1",result.getName());
        assertTrue(result.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_USER")));
    }
}
