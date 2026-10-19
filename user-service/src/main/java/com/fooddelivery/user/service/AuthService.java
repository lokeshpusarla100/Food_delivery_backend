package com.fooddelivery.user.service;

import com.fooddelivery.user.dto.AuthDtos.*;
import com.fooddelivery.user.dto.UserResponse;
import com.fooddelivery.user.entity.*;
import com.fooddelivery.user.exception.ApiException;
import com.fooddelivery.user.repository.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class AuthService {
    private static final Logger log=LoggerFactory.getLogger(AuthService.class);
    private final UserRepository users;
    private final RefreshSessionRepository sessions;
    private final LoginAttemptRepository loginAttempts;
    private final LoginAttemptRecorder recorder;
    private final PasswordResetTokenRepository resetTokens;
    private final EmailVerificationTokenRepository verifyTokens;
    private final PasswordEncoder passwords;
    private final JwtEncoder jwtEncoder;
    private final TokenFactory tokens;
    private final EmailSender emailSender;
    private final Clock clock;

    @Value("${security.jwt.issuer}") private String issuer;
    @Value("${security.jwt.audience}") private String audience;
    @Value("${security.jwt.access-ttl:PT15M}") private Duration accessTtl;
    @Value("${security.jwt.refresh-ttl:P14D}") private Duration refreshTtl;
    @Value("${security.auth.failure-window:PT15M}") private Duration failureWindow;
    @Value("${security.auth.max-failures:5}") private long maxFailures;
    private String dummyPasswordHash;

    @PostConstruct
    void prepareDummyHash() { dummyPasswordHash = passwords.encode(UUID.randomUUID().toString()); }

    public AuthResponse login(LoginRequest req, String remoteIp) {
        String email=UserService.normalizedEmail(req.email());
        LocalDateTime threshold=LocalDateTime.now(clock).minus(failureWindow);
        if(loginAttempts.countByIdentifierAndAttemptStatusAndAttemptedAtAfter(email,"FAILED",threshold)>=maxFailures) {
            recorder.record(email,remoteIp,"BLOCKED");
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"TOO_MANY_ATTEMPTS","Try again later");
        }
        Optional<User> found = users.findByEmail(email);
        String hash = found.map(User::getPasswordHash).orElse(dummyPasswordHash);
        boolean valid=passwords.matches(req.password(),hash) && found.isPresent() && found.get().isActive();
        recorder.record(email,remoteIp, valid ? "SUCCESS" : "FAILED");
        if(!valid) throw invalidCredentials();
        return issueTokens(found.get(),req.deviceId());
    }

    private ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED,"INVALID_CREDENTIALS","Invalid credentials");
    }
    private String accessToken(User user) {
        Instant now=clock.instant();
        JwtClaimsSet claims=JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience))
            .subject(user.getUserId()).issuedAt(now).notBefore(now)
            .expiresAt(now.plus(accessTtl)).id(UUID.randomUUID().toString())
            .claim("ver",user.getTokenVersion()).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }
    private AuthResponse issueTokens(User user, String deviceId) {
        String raw=tokens.newToken();
        RefreshSession session=new RefreshSession();
        session.setUserId(user.getUserId());
        session.setDeviceId(deviceId);
        session.setTokenHash(tokens.hash(raw));
        session.setCreatedAt(LocalDateTime.now(clock));
        session.setExpiresAt(LocalDateTime.now(clock).plus(refreshTtl));
        sessions.save(session);
        return new AuthResponse(accessToken(user),raw,"Bearer",accessTtl.toSeconds(),
            UserService.toResponse(user));
    }
    @Transactional
    public AuthResponse refresh(RefreshRequest req) {
        RefreshSession session=sessions.lockByTokenHash(tokens.hash(req.refreshToken()))
            .orElseThrow(this::invalidCredentials);
        if(session.isRevoked() || !session.getExpiresAt().isAfter(LocalDateTime.now(clock)))
            throw invalidCredentials();
        User user=users.findById(session.getUserId()).orElseThrow(this::invalidCredentials);
        if(!user.isActive()) throw invalidCredentials();
        session.setRevoked(true);
        sessions.saveAndFlush(session);
        return issueTokens(user,session.getDeviceId());
    }
    @Transactional
    public void logout(String userId, LogoutRequest req) {
        sessions.lockByTokenHash(tokens.hash(req.refreshToken())).ifPresent(s-> {
            if(!s.getUserId().equals(userId)) throw invalidCredentials();
            s.setRevoked(true);
        });
    }
    @Transactional
    public void logoutAll(String userId) {
        User user=users.findById(userId).orElseThrow(this::invalidCredentials);
        user.setTokenVersion(user.getTokenVersion()+1);
        sessions.revokeAll(userId);
        users.save(user);
    }
    @Transactional
    public void changePassword(String userId, PasswordChangeRequest req) {
        UserService.validatePassword(req.newPassword());
        User user=users.findById(userId).orElseThrow(this::invalidCredentials);
        if(!user.isActive() || !passwords.matches(req.currentPassword(),user.getPasswordHash()))
            throw invalidCredentials();
        if(passwords.matches(req.newPassword(),user.getPasswordHash()))
            throw new ApiException(HttpStatus.BAD_REQUEST,"PASSWORD_REUSED","Use a different password");
        user.setPasswordHash(passwords.encode(req.newPassword()));
        user.setTokenVersion(user.getTokenVersion()+1);
        sessions.revokeAll(userId);
        users.save(user);
    }

    @Transactional
    public void requestEmailVerification(String userId) {
        User user=users.findById(userId).orElseThrow(this::invalidCredentials);
        if(!user.isActive() || user.isVerified()) return;
        if(verifyTokens.countByUserIdAndCreatedAtAfter(userId, LocalDateTime.now(clock).minusMinutes(5))>0) return;
        String raw=tokens.newToken();
        EmailVerificationToken token=new EmailVerificationToken();
        token.setUserId(userId);token.setTokenHash(tokens.hash(raw));
        token.setCreatedAt(LocalDateTime.now(clock));
        token.setExpiresAt(LocalDateTime.now(clock).plusMinutes(30));
        verifyTokens.save(token);
        emailSender.sendToken(user.getEmail(),"verify-email",raw);
    }
    @Transactional
    public void verifyEmail(VerifyEmailRequest req) {
        EmailVerificationToken token=verifyTokens.lockByTokenHash(tokens.hash(req.token()))
            .orElseThrow(this::invalidCredentials);
        if(token.isConsumed() || !token.getExpiresAt().isAfter(LocalDateTime.now(clock)))
            throw invalidCredentials();
        User user=users.findById(token.getUserId()).orElseThrow(this::invalidCredentials);
        if(!user.isActive()) throw invalidCredentials();
        user.setVerified(true);
        token.setConsumed(true);
    }
    public void requestPasswordReset(String email) {
        users.findByEmail(UserService.normalizedEmail(email)).ifPresent(user->{
            if(!user.isActive()) return;
            if(resetTokens.countByUserIdAndCreatedAtAfter(
                    user.getUserId(), LocalDateTime.now(clock).minusMinutes(5))>0) return;
            String raw=tokens.newToken();
            PasswordResetToken token=new PasswordResetToken();
            token.setUserId(user.getUserId());token.setTokenHash(tokens.hash(raw));
            token.setCreatedAt(LocalDateTime.now(clock));
            token.setExpiresAt(LocalDateTime.now(clock).plusMinutes(20));
            PasswordResetToken saved=resetTokens.save(token);
            try {
                emailSender.sendToken(user.getEmail(),"password-reset",raw);
            } catch (ApiException ex) {
                // Never expose account existence by returning a different status for existing emails.
                resetTokens.delete(saved);
                log.warn("Password reset email unavailable for userId={}",user.getUserId());
            }
        });
    }
    @Transactional
    public void confirmPasswordReset(String raw, String newPassword) {
        UserService.validatePassword(newPassword);
        PasswordResetToken token=resetTokens.lockByTokenHash(tokens.hash(raw))
            .orElseThrow(this::invalidCredentials);
        if(token.isConsumed() || !token.getExpiresAt().isAfter(LocalDateTime.now(clock)))
            throw invalidCredentials();
        User user=users.findById(token.getUserId()).orElseThrow(this::invalidCredentials);
        if(!user.isActive()) throw invalidCredentials();
        user.setPasswordHash(passwords.encode(newPassword));
        user.setTokenVersion(user.getTokenVersion()+1);
        sessions.revokeAll(user.getUserId());
        token.setConsumed(true);
    }
}
