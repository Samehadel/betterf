package com.theeverthread.identity.internal.repository;

import com.theeverthread.identity.internal.entity.OAuthIdentityEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OAuthIdentityRepository extends JpaRepository<OAuthIdentityEntity, UUID> {
    Optional<OAuthIdentityEntity> findByProviderAndSubjectId(String provider, String subjectId);
}
