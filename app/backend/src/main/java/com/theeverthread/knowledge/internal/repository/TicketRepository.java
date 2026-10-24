package com.theeverthread.knowledge.internal.repository;

import com.theeverthread.knowledge.internal.entity.TicketEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<TicketEntity, UUID> {
    Optional<TicketEntity> findByAuthorIdAndSubmissionId(UUID authorId, UUID submissionId);

    Optional<TicketEntity> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
}
