package com.betterf.identity.internal.repository;

import com.betterf.identity.internal.entity.EmailDeliveryStatus;
import com.betterf.identity.internal.entity.VerificationEmailEntity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface VerificationEmailRepository extends JpaRepository<VerificationEmailEntity, UUID> {
    @Query(
            """
            select e.accountId from VerificationEmailEntity e
            where e.status in :statuses and e.nextAttemptAt <= :now
            order by e.nextAttemptAt, e.accountId
            """)
    List<UUID> due(Collection<EmailDeliveryStatus> statuses, Instant now, Pageable page);
}
