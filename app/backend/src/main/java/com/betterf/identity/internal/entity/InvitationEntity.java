package com.betterf.identity.internal.entity;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "TEAM_INVITATION")
@Getter
@Setter
public class InvitationEntity {
    @Id private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ORGANIZATION_ID", nullable = false)
    private OrganizationEntity organization;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(nullable = false, length = 200)
    private String administratorName;

    @Column(nullable = false)
    private UUID administratorId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant attemptedAt;

    private Instant sentAt;

    private Instant acceptedAt;

    @Column(length = 64)
    private String acceptedTokenHash;

    @Column(length = 255)
    private String smtpMessageId;

    @Column(length = 50)
    private String failureCode;
}
