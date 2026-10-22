package com.betterf.knowledge.internal.entity;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "KNOWLEDGE_TICKET",
        uniqueConstraints = @UniqueConstraint(columnNames = {"AUTHOR_ID", "SUBMISSION_ID"}))
@Getter
@Setter
public class TicketEntity {
    @Id private UUID id;

    @Column(nullable = false)
    private UUID authorId;

    @Column private UUID workspaceId;

    @Column(nullable = false, length = 200)
    private String authorName;

    @Column(nullable = false)
    private UUID submissionId;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false, length = 20)
    private String classificationStatus;

    @Column(nullable = false, columnDefinition = "text")
    private String attributes;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    private UUID leaseToken;
    private Instant leaseUntil;
}
