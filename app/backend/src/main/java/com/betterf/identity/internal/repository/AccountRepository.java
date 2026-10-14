package com.betterf.identity.internal.repository;

import com.betterf.identity.internal.entity.*;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

import java.time.Instant;
import java.util.*;

public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {
    Optional<AccountEntity> findByEmail(String email);

    @Query(
            "select a from AccountEntity a where a.organization.id = :organizationId"
                    + " and a.status = com.betterf.identity.internal.entity.AccountStatus.ACTIVE"
                    + " and a.accessRole = 'MEMBER' order by a.updatedAt desc, a.id desc")
    List<AccountEntity> activeMembers(UUID organizationId);

    long countByOrganizationIdAndStatus(UUID organizationId, AccountStatus status);

    @Query("select a.email from AccountEntity a where a.id = :id")
    Optional<String> emailForId(UUID id);

    @Query(
            "select a.email from AccountEntity a where a.status ="
                + " com.betterf.identity.internal.entity.AccountStatus.PENDING and a.updatedAt <"
                + " :cutoff order by a.updatedAt")
    List<String> abandonedEmails(Instant cutoff, Pageable page);
}
