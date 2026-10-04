package com.betterf.identity.internal.entity;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ACCOUNT")
@Getter
@Setter
public class AccountEntity {
    @Id private UUID id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(nullable = false, length = 200)
    private String fullName;

    @Column(nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false, length = 80)
    private String professionalRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ORGANIZATION_ID", nullable = false)
    private OrganizationEntity organization;

    @Column(length = 20)
    private String accessRole;

    @Column(nullable = false)
    private Instant updatedAt;
}
