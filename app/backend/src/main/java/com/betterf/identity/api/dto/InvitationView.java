package com.betterf.identity.api.dto;

import java.time.Instant;
import java.util.UUID;

public record InvitationView(
        UUID id, String email, String status, Instant expiresAt, String message) {}
