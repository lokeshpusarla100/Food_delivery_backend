package com.fooddelivery.user;

import com.fooddelivery.user.dto.AuthDtos.*;
import com.fooddelivery.user.entity.*;
import com.fooddelivery.user.exception.ApiException;
import com.fooddelivery.user.repository.*;
import com.fooddelivery.user.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock UserRepository users;
    @Mock RefreshSessionRepository sessions;
    @Mock LoginAttemptRepository loginAttempts;
    @Mock LoginAttemptRecorder recorder;
    @Mock PasswordResetTokenRepository resetTokens;
    @Mock EmailVerificationTokenRepository verifyTokens;
    @Mock PasswordEncoder passwords;
    @Mock JwtEncoder jwtEncoder;
    @Mock TokenFactory tokens;
    @Mock EmailSender emailSender;
    final Clock clock=Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"),ZoneOffset.UTC);
    AuthService auth;

    @BeforeEach void setup() {
        auth=new AuthService(users,sessions,loginAttempts,recorder,resetTokens,verifyTokens,
            passwords,jwtEncoder,tokens,emailSender,clock);
        ReflectionTestUtils.setField(auth,"issuer","food-delivery-user-service");
        ReflectionTestUtils.setField(auth,"audience","food-delivery-api");
        ReflectionTestUtils.setField(auth,"accessTtl",Duration.ofMinutes(15));
        ReflectionTestUtils.setField(auth,"refreshTtl",Duration.ofDays(14));
        ReflectionTestUtils.setField(auth,"failureWindow",Duration.ofMinutes(15));
        ReflectionTestUtils.setField(auth,"maxFailures",5L);
    }
    @Test void refreshRevokesOldTokenAndBlocksReplay() {
        RefreshSession old=new RefreshSession();
        old.setUserId("u-1");old.setDeviceId("android");
        old.setExpiresAt(LocalDateTime.now(clock).plusDays(2));
        when(tokens.hash("old")).thenReturn("old-hash");
        when(sessions.lockByTokenHash("old-hash")).thenReturn(Optional.of(old));
        when(users.findById("u-1")).thenReturn(Optional.of(
            User.builder().userId("u-1").isActive(true).build()));
        when(tokens.newToken()).thenReturn("new");
        when(tokens.hash("new")).thenReturn("new-hash");
        when(jwtEncoder.encode(any())).thenReturn(
            new Jwt("access",clock.instant(),clock.instant().plusSeconds(900),
                Map.of("alg","HS256"),Map.of("sub","u-1")));
        AuthResponse result=auth.refresh(new RefreshRequest("old"));
        assertEquals("new",result.refreshToken());
        assertTrue(old.isRevoked());
        verify(sessions).saveAndFlush(old);
        verify(sessions).save(argThat(s -> "new-hash".equals(s.getTokenHash())));
        assertThrows(ApiException.class,()->auth.refresh(new RefreshRequest("old")));
    }
    @Test void logoutAllBumpsVersionAndRevokesSessions() {
        User user=User.builder().userId("u-1").isActive(true).tokenVersion(4).build();
        when(users.findById("u-1")).thenReturn(Optional.of(user));
        auth.logoutAll("u-1");
        assertEquals(5L,user.getTokenVersion());
        verify(sessions).revokeAll("u-1");
    }
}
