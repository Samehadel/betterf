package com.betterf.identity.internal.entity;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(
        name = "OAUTH_IDENTITY",
        uniqueConstraints = @UniqueConstraint(columnNames = {"PROVIDER", "SUBJECT_ID"}))
@Getter
@Setter
public class OAuthIdentityEntity {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ACCOUNT_ID", nullable = false)
    private AccountEntity account;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(nullable = false, length = 255)
    private String subjectId;
}
