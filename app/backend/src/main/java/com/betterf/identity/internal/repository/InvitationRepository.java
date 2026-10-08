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

    Slice<InvitationEntity> findByOrganizationId(UUID organizationId, Pageable page);

    Optional<InvitationEntity> findByOrganizationIdAndEmail(UUID organizationId, String email);
}
