package com.theeverthread.foundation.internal.security;

import com.theeverthread.identity.api.dto.SocialIdentityView;
import com.theeverthread.identity.api.exception.IdentityException;
import com.theeverthread.identity.api.service.SocialIdentityService;

import jakarta.servlet.http.*;

import lombok.RequiredArgsConstructor;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SocialLoginHandler implements AuthenticationSuccessHandler {
    private final SocialIdentityService identities;
    private final SessionLogin login;
    private final SecurityContextRepository contexts;
    private final Clock clock;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        var oauth = (OAuth2AuthenticationToken) authentication;
        var user = oauth.getPrincipal();
        Object verified = user.getAttribute("email_verified");
        if (user instanceof OidcUser oidc
                && Boolean.FALSE.equals(oidc.getIdToken().getEmailVerified())) verified = false;
        var identity =
                new SocialIdentityView(
                        oauth.getAuthorizedClientRegistrationId(),
                        user.getName(),
                        user.getAttribute("email"),
                        user.getAttribute("name"),
                        verified instanceof Boolean value ? value : null);
        // OAuth principals must never authorize product APIs until domain validation succeeds.
        clearAuthentication(request, response);
        try {
            var account = identities.authenticate(identity);
            var principal =
                    UsernamePasswordAuthenticationToken.authenticated(
                            account.email(),
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + account.accessRole())));
            login.establish(principal, request, response);
            SocialLoginState.authenticated(request, account.email(), clock.instant());
            response.sendRedirect(
                    SocialLoginState.pending(request, clock.instant()) == null
                            ? "/knowledge"
                            : "/link-account");
        } catch (IdentityException failure) {
            if (failure.code().equals("LINK_REQUIRED")) {
                SocialLoginState.pending(request, identity, clock.instant());
                response.sendRedirect("/login?social=LINK_REQUIRED");
            } else {
                response.sendRedirect("/login?social=" + failure.code());
            }
        } catch (RuntimeException failure) {
            clearAuthentication(request, response);
            response.sendRedirect("/login?social=PROVIDER_UNAVAILABLE");
        }
    }

    private void clearAuthentication(HttpServletRequest request, HttpServletResponse response) {
        var context = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
    }
}
