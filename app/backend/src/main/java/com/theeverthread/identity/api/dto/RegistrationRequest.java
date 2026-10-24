package com.theeverthread.identity.api.dto;

import jakarta.validation.constraints.*;

public record RegistrationRequest(
        @NotBlank(message = "Full name is required.") @Size(max = 200) String fullName,
        @NotBlank(message = "Email is required.")
                @Email(message = "Enter a valid email address.")
                @Size(max = 254)
                String email,
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
