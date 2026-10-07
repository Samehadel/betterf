package com.betterf.identity.internal.repository;
import com.betterf.identity.internal.entity.InvitationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface InvitationRepository extends JpaRepository<InvitationEntity, UUID> {
    Optional<InvitationEntity> findByOrganizationIdAndEmail(UUID organizationId, String email);
}
