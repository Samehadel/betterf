package com.betterf.identity.api.dto;

import jakarta.validation.constraints.*;

import java.util.UUID;

public record InvitationLinkRequest(
        @NotNull
        UUID id,
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9_-]{43}")
        String token) {}
