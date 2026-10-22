package com.betterf.foundation.internal.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.*;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

import java.util.List;

/** Only optional profile lookup outages may fall back to already validated ID-token claims. */
@Component
public class SocialOidcUsers
        implements org.springframework.security.oauth2.client.userinfo.OAuth2UserService<
                OidcUserRequest, OidcUser> {
    private final OidcUserService delegate;

    public SocialOidcUsers() {
        delegate = new OidcUserService();
        var profiles = new DefaultOAuth2UserService();
        delegate.setOauth2UserService(
                request -> {
                    try {
                        return profiles.loadUser(request);
                    } catch (OAuth2AuthenticationException failure) {
                        if (request instanceof OidcUserRequest oidc
                                && optionalLookupOutage(failure)
                                && oidc.getIdToken().getClaimAsString("email") != null) {
                            return new DefaultOAuth2User(
                                    List.of(new SimpleGrantedAuthority("OIDC_USER")),
                                    oidc.getIdToken().getClaims(),
                                    "sub");
                        }
                        throw failure;
                    }
                });
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        return delegate.loadUser(request);
    }

    private boolean optionalLookupOutage(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ResourceAccessException) return true;
            if (cause instanceof RestClientResponseException response
                    && response.getStatusCode().is5xxServerError()) return true;
        }
        return false;
    }
}
