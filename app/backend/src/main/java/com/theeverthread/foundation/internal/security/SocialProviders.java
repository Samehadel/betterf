package com.theeverthread.foundation.internal.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;

import java.util.*;

@Configuration
public class SocialProviders {
    @Bean
    org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository
            ephemeralProviderClients() {
        return new org.springframework.security.oauth2.client.web
                .OAuth2AuthorizedClientRepository() {
            @Override
            public <T extends org.springframework.security.oauth2.client.OAuth2AuthorizedClient>
                    T loadAuthorizedClient(
                            String registrationId,
                            org.springframework.security.core.Authentication principal,
                            jakarta.servlet.http.HttpServletRequest request) {
                return null;
            }

            @Override
            public void saveAuthorizedClient(
                    org.springframework.security.oauth2.client.OAuth2AuthorizedClient client,
                    org.springframework.security.core.Authentication principal,
                    jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response) {}

            @Override
            public void removeAuthorizedClient(
                    String registrationId,
                    org.springframework.security.core.Authentication principal,
                    jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response) {}
        };
    }

    @Bean
    ClientRegistrationRepository socialRegistrations(Environment configuration) {
        var registrations = new HashMap<String, ClientRegistration>();
        for (String provider : List.of("linkedin", "facebook")) {
            String prefix = "theeverthread.oauth." + provider + ".";
            String id = configuration.getProperty(prefix + "client-id", "");
            String secret = configuration.getProperty(prefix + "client-secret", "");
            if (id.isBlank() || secret.isBlank()) continue;
            String origin = configuration.getRequiredProperty("theeverthread.registration.public-origin");
            var builder =
                    ClientRegistration.withRegistrationId(provider)
                            .clientId(id)
                            .clientSecret(secret)
                            .clientAuthenticationMethod(
                                    ClientAuthenticationMethod.CLIENT_SECRET_POST)
                            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                            .redirectUri(origin + "/api/auth/oauth/callback/" + provider)
                            .clientName(provider);
            if (provider.equals("linkedin")) {
                builder.scope("openid", "profile", "email")
                        .authorizationUri("https://www.linkedin.com/oauth/v2/authorization")
                        .tokenUri("https://www.linkedin.com/oauth/v2/accessToken")
                        .jwkSetUri("https://www.linkedin.com/oauth/openid/jwks")
                        .issuerUri("https://www.linkedin.com")
                        .userInfoUri("https://api.linkedin.com/v2/userinfo")
                        .userNameAttributeName("sub");
            } else {
                String version = configuration.getProperty(prefix + "api-version", "v23.0");
                if (!version.matches("v[0-9]+\\.[0-9]+"))
                    throw new IllegalArgumentException("Invalid Facebook API version");
                builder.scope("public_profile", "email")
                        .authorizationUri("https://www.facebook.com/" + version + "/dialog/oauth")
                        .tokenUri("https://graph.facebook.com/" + version + "/oauth/access_token")
                        .userInfoUri(
                                "https://graph.facebook.com/"
                                        + version
                                        + "/me?fields=id,name,email")
                        .userNameAttributeName("id");
            }
            registrations.put(provider, builder.build());
        }
        return registrations::get;
    }
}
