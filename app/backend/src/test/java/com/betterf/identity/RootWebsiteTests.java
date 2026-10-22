package com.betterf.identity;

import static org.assertj.core.api.Assertions.*;

import com.betterf.identity.api.dto.RegistrationRequest;
import com.betterf.identity.internal.service.IdentityServiceImpl;

import jakarta.validation.Validation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Signup validates an account email; website/domain eligibility is no longer a product rule. */
class RootWebsiteTests {
    @ParameterizedTest
    @ValueSource(strings = {"User@Company.com", " USER@COMPANY.COM "})
    void canonicalIdentity(String email) {
        assertThat(IdentityServiceImpl.normalizeEmail(email)).isEqualTo("user@company.com");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"user@company.co.uk", "user@division.company.com", "user@tenant.github.io"})
    void acceptsEmailsWithoutRootWebsiteRestrictions(String email) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(
                            factory.getValidator()
                                    .validate(new RegistrationRequest("User", email, "Validpass!")))
                    .isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-an-email", "https://company.com", "user@", "@company.com"})
    void rejectsInvalidAccountEmails(String email) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(
                            factory.getValidator()
                                    .validate(new RegistrationRequest("User", email, "Validpass!")))
                    .isNotEmpty();
        }
    }
}
