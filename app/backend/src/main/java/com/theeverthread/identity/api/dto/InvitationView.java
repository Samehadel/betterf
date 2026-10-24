package com.theeverthread.identity.api.dto;

import java.time.Instant;
import java.util.UUID;

public record InvitationView(
        UUID id,
        String email,
        String status,
        Instant expiresAt,
        String message,
        Instant sentAt,
        Instant resendAvailableAt,
        boolean resending,
        boolean deliveryFailed) {
    public InvitationView(UUID id, String email, String status, Instant expiresAt, String message) {
        this(id, email, status, expiresAt, message, null, null, false, false);
    }
}
