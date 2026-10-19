package com.fooddelivery.user.repository;

import com.fooddelivery.user.entity.EmailVerificationToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.time.LocalDateTime;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from EmailVerificationToken t where t.tokenHash = :hash")
    Optional<EmailVerificationToken> lockByTokenHash(@Param("hash") String hash);
    long countByUserIdAndCreatedAtAfter(String userId, LocalDateTime since);
}
