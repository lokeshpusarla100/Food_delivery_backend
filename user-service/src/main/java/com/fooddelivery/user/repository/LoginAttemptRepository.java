package com.fooddelivery.user.repository;

import com.fooddelivery.user.entity.LoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {
    long countByIdentifierAndAttemptStatusAndAttemptedAtAfter(
        String identifier, String attemptStatus, LocalDateTime after);
}
