package com.theeverthread.identity.internal.repository;

import com.theeverthread.identity.internal.entity.MembershipEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

public interface MembershipRepository extends JpaRepository<MembershipEntity, UUID> {
    Optional<MembershipEntity> findByAccountIdAndWorkspaceId(UUID accountId, UUID workspaceId);

    Optional<MembershipEntity> findByAccountIdAndWorkspaceIdAndActiveTrue(
            UUID accountId, UUID workspaceId);

    List<MembershipEntity> findByAccountIdAndActiveTrueOrderByWorkspaceNameAsc(UUID accountId);

    List<MembershipEntity> findByWorkspaceIdAndActiveTrue(UUID workspaceId);

    long countByWorkspaceIdAndActiveTrue(UUID workspaceId);
}
