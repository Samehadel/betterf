package com.betterf.foundation.controller;

import com.betterf.foundation.internal.security.SessionLogin;
import com.betterf.identity.api.dto.IdentityViews.VerificationView;
import com.betterf.identity.api.service.IdentityService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class VerificationController {
    private final IdentityService identity;
    private final SessionLogin login;

    public VerificationController(IdentityService identity, SessionLogin login) {
        this.identity = identity;
        this.login = login;
    }

    public record VerifyRequest(@NotNull UUID id, @NotBlank @Size(max = 100) String token) {}

    @PostMapping("/api/registration/verify")
    public VerificationView verify(
            @Valid @RequestBody VerifyRequest body,
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication principal) {
        // Identity's transaction commits activation before any authenticated session is saved.
        var result = identity.verify(body.id(), body.token());
        if (result.account() != null) {
            var account = result.account();
            var authentication =
                    UsernamePasswordAuthenticationToken.authenticated(
                            account.email(),
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + account.accessRole())));
            login.establish(authentication, request, response);
            return result;
        }
        // An already-used verification link is never a reusable login credential. A browser
        // that already holds this account's authenticated session can return home safely.
        if (principal != null && principal.isAuthenticated()) {
            var current = identity.current(principal.getName());
            if (current.id().equals(body.id())) {
                return new VerificationView(result.status(), current);
            }
        }
        return result;
    }
}
