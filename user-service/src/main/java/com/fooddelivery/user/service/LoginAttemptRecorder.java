package com.fooddelivery.user.service;

import com.fooddelivery.user.entity.LoginAttempt;
import com.fooddelivery.user.repository.LoginAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;

@Service @RequiredArgsConstructor
public class LoginAttemptRecorder {
    private final LoginAttemptRepository repository;
    private final Clock clock;

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void record(String email, String ip, String status) {
        LoginAttempt attempt = new LoginAttempt();
        attempt.setIdentifier(email);
        attempt.setIdentifierType("EMAIL");
        attempt.setIpAddress(ip == null ? null : ip.substring(0, Math.min(ip.length(),45)));
        attempt.setAttemptStatus(status);
        attempt.setFailureReason("SUCCESS".equals(status) ? null : "INVALID_CREDENTIALS");
        attempt.setAttemptedAt(LocalDateTime.now(clock));
        repository.save(attempt);
    }
}
