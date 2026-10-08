package com.betterf.foundation.controller;

import com.betterf.foundation.internal.security.RefreshSessions;
import com.betterf.foundation.internal.security.SessionLogin;
import com.betterf.identity.api.dto.*;
import com.betterf.identity.api.dto.IdentityViews.AccountView;
import com.betterf.identity.api.service.InvitationAcceptanceService;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** HTTP session orchestration around Identity's committed registration transaction. */
@RestController
@RequestMapping("/api/auth/invitation")
@RequiredArgsConstructor
public class InvitationAcceptanceController {
    private final InvitationAcceptanceService acceptance;
    private final SessionLogin login;
    private final RefreshSessions refresh;

    @PostMapping("/preview")
    public InvitationAcceptanceView preview(
            @Valid
            @RequestBody
            InvitationLinkRequest body,
            Authentication principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        var result = acceptance.preview(body);
        if (result.email() != null
                && (principal == null || !result.email().equalsIgnoreCase(principal.getName()))) {
            clearSession(principal, request, response);
        }
        return result;
    }

    @PostMapping("/accept")
    public AccountView accept(
            @Valid
            @RequestBody
            MemberRegistrationRequest body,
            Authentication principal,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        // Commit acceptance before session creation. A retry sees the used-link result, never a
        // login grant.
        var account = acceptance.accept(body);
        clearSession(principal, request, response);
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        account.email(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + account.accessRole())));
        login.establish(authentication, request, response);
        return account;
    }

    private void clearSession(
            Authentication principal, HttpServletRequest request, HttpServletResponse response) {
        refresh.revoke(request);
        refresh.write(response, null);
        new SecurityContextLogoutHandler().logout(request, response, principal);
    }
}
