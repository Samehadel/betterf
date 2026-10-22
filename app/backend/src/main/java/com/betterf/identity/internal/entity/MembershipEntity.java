package com.betterf.identity.internal.entity;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** A person's role and access belong to a workspace, never to their global account. */
@Entity
@Table(
        name = "WORKSPACE_MEMBERSHIP",
        uniqueConstraints = @UniqueConstraint(columnNames = {"ACCOUNT_ID", "WORKSPACE_ID"}))
@Getter
@Setter
public class MembershipEntity {
    @Id private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ACCOUNT_ID", nullable = false)
    private AccountEntity account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "WORKSPACE_ID", nullable = false)
    private WorkspaceEntity workspace;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private boolean active = true;
}
