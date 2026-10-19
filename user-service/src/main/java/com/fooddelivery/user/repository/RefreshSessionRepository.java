package com.fooddelivery.user.repository;

import com.fooddelivery.user.entity.RefreshSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshSession s where s.tokenHash = :hash")
    Optional<RefreshSession> lockByTokenHash(@Param("hash") String hash);

    @Modifying
    @Query("update RefreshSession s set s.revoked = true where s.userId = :userId and s.revoked = false")
    int revokeAll(@Param("userId") String userId);
}
