package com.betterf.identity.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record MemberRegistrationRequest(
        @NotNull
        @Valid
        InvitationLinkRequest invitation,
        @NotBlank(message = "Full name is required.")
        @Size(max = 200)
        String fullName,
        @NotBlank(message = "Choose a professional role.")
        String professionalRole,
        @NotNull
                @Size(
                        min = 10,
                        max = 128,
                        message =
                                "Use 10–128 characters, including at least one uppercase letter and"
                                    + " one special character.")
                @Pattern(
                        regexp = "(?s)(?=.*\\p{Lu})(?=.*[\\p{P}\\p{S}]).*",
                        message =
                                "Use 10–128 characters, including at least one uppercase letter and"
                                    + " one special character.")
                String password) {}
