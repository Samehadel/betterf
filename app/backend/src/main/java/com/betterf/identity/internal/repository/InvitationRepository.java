package com.betterf.identity.internal.repository;

import com.betterf.identity.internal.entity.InvitationEntity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.*;

public interface InvitationRepository extends JpaRepository<InvitationEntity, UUID> {
    @Query("select i.email from InvitationEntity i where i.id = :id")
    Optional<String> emailForId(UUID id);

    @Query(
            "select i from InvitationEntity i where i.organization.id = :organizationId and"
                    + " (i.status in ('FAILED', 'ACCEPTED') or"
                    + " (i.status = 'SMTP_ACCEPTED' and i.expiresAt > :now))"
                    + " order by coalesce(i.sentAt, i.attemptedAt) desc, i.id desc")
    List<InvitationEntity> managementRecords(UUID organizationId, java.time.Instant now);

    Slice<InvitationEntity> findByOrganizationId(UUID organizationId, Pageable page);

    Optional<InvitationEntity> findByOrganizationIdAndEmail(UUID organizationId, String email);
}
