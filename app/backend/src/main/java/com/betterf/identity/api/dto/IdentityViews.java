package com.betterf.identity.api.dto;

import java.time.Instant;
import java.util.UUID;

public final class IdentityViews {
    private IdentityViews() {}

    public record PendingView(String email, Instant resendAvailableAt, String deliveryStatus) {}

    public record VerificationView(String status) {}

    public record AccountView(
            UUID id,
            String fullName,
            String email,
            String professionalRole,
            UUID organizationId,
            String organizationName,
            String accessRole) {}

    public record CredentialsView(
            String email, String passwordHash, String accessRole, boolean active) {}

    public record RoleView(String id, String label) {}
}
