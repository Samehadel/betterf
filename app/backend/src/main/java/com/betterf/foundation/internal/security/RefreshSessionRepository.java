package com.betterf.foundation.internal.security;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshSession s where s.id = :id")
    Optional<RefreshSession> lock(@Param("id") UUID id);

    @Modifying
    @Query("delete from RefreshSession s where s.expiresAt <= :now")
    void deleteExpired(@Param("now") Instant now);
}
