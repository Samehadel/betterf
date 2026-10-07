package com.betterf.identity.api.dto;

import jakarta.validation.constraints.*;

public record InvitationRequest(
        @NotBlank(message = "Enter an email address.")
                @Email(message = "Enter a valid email address.")
                @Size(max = 254, message = "Email must be at most 254 characters.")
                String email) {}
