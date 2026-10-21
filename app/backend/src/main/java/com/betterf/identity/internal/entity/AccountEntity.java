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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "DEFAULT_WORKSPACE_ID")
    private WorkspaceEntity defaultWorkspace;

    @Column(nullable = false)
    private Instant updatedAt;

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }
}
