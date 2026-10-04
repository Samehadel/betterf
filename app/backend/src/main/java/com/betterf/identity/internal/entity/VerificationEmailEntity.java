package com.betterf.identity.internal.entity;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One reusable delivery/verification record per account; credentials never live on the account. */
@Entity
@Table(name = "VERIFICATION_EMAIL")
@Getter
@Setter
public class VerificationEmailEntity {
    @Id private UUID accountId;

    @Column(nullable = false, length = 254)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmailDeliveryStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private Instant lastAttemptAt;

    private Instant lastSentAt;

    @Column(length = 50)
    private String failureCode;

    @Column(length = 64)
    private String tokenHash;

    private Instant tokenExpiresAt;
}
