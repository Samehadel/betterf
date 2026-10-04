package com.betterf.identity;

import static org.assertj.core.api.Assertions.*;

import com.betterf.identity.internal.service.RootWebsite;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RootWebsiteTests {
    @ParameterizedTest
    @ValueSource(strings = {"https://company.com", "https://COMPANY.COM/"})
    void canonicalIdentity(String website) {
        assertThat(RootWebsite.domain(website)).isEqualTo("company.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://company.co.uk", "https://company.com.au"})
    void multiPartRegistrySuffixes(String website) {
        assertThat(RootWebsite.domain(website)).isEqualTo(website.substring(8));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://company.com",
                "https://www.company.com",
                "https://division.company.com",
                "https://company.com/about",
                "https://company.com?x=y",
                "https://company.com#x",
                "https://user@company.com",
                "https://company.com:443",
                "https://co.uk",
                "https://company.invalid",
                "https://127.0.0.1",
                "https://localhost",
                "https://company.com.",
                "https://company.com/%2f",
                "https://company.com\\evil",
                "https://division.company.co.uk",
                "https://tenant.github.io"
            })
    void rejectsNonRootWebsites(String website) {
        assertThatThrownBy(() -> RootWebsite.domain(website))
                .hasMessageContaining("HTTPS root website");
    }
}
