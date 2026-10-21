package com.betterf.identity.api.dto;

import java.time.Instant;
import java.util.UUID;

public final class IdentityViews {
    private IdentityViews() {}

    public record PendingView(String email, Instant resendAvailableAt, String deliveryStatus) {}

    public record VerificationView(String status, AccountView account) {}

    public record WorkspaceView(UUID id, String name, String role) {}

    public record AccountView(
            UUID id,
            String fullName,
            String email,
            UUID workspaceId,
            String workspaceName,
            String accessRole,
            java.util.List<WorkspaceView> workspaces) {}

    public record CredentialsView(
            String email, String passwordHash, String accessRole, boolean active) {}
}
