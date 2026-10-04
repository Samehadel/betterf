package com.betterf.identity.api.dto;

import jakarta.validation.constraints.*;

public record RegistrationRequest(
        @NotBlank(message = "Company name is required.") @Size(max = 200) String companyName,
        @NotBlank(message = "Website is required.") @Size(max = 253) String website,
        @NotBlank(message = "Specialization is required.") @Size(max = 200) String specialization,
        @NotBlank(message = "Full name is required.") @Size(max = 200) String fullName,
        @NotBlank(message = "Organization email is required.")
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
                String password,
        @NotBlank(message = "Choose a professional role.") String professionalRole) {}
