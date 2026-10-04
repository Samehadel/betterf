package com.betterf.foundation.controller;

import com.betterf.foundation.internal.security.SessionLogin;
import com.betterf.identity.api.dto.IdentityViews.AccountView;
import com.betterf.identity.api.exception.IdentityException;
import com.betterf.identity.api.service.IdentityService;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.security.authentication.*;
import org.springframework.security.core.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class SessionController {
    private final AuthenticationManager authentication;
    private final SessionLogin login;
    private final IdentityService identity;

    public SessionController(
            AuthenticationManager authentication, SessionLogin login, IdentityService identity) {
        this.authentication = authentication;
        this.login = login;
        this.identity = identity;
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotNull @Size(max = 128) String password) {}

    public record CsrfView(String token, String headerName) {}

    @GetMapping("/csrf")
    public CsrfView csrf(CsrfToken token) {
        return new CsrfView(token.getToken(), token.getHeaderName());
    }

    @PostMapping("/login")
    public AccountView login(
            @Valid @RequestBody LoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            var principal =
                    authentication.authenticate(
                            UsernamePasswordAuthenticationToken.unauthenticated(
                                    body.email().trim().toLowerCase(java.util.Locale.ROOT),
                                    body.password()));
            login.establish(principal, request, response);
            return identity.current(principal.getName());
        } catch (AuthenticationException exception) {
            throw new IdentityException(
                    401,
                    "LOGIN_FAILED",
                    "Unable to log in. Check your email and password and verify your email before"
                            + " trying again.");
        }
    }

    @GetMapping("/me")
    public AccountView me(Authentication principal) {
        return identity.current(principal.getName());
    }
}
