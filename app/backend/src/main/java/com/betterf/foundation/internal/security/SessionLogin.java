package com.betterf.foundation.internal.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/** Shared session rotation and persistence for password login and first-time verification. */
@Component
public class SessionLogin {
    private final RefreshSessions refresh;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessions;

    public SessionLogin(
            SecurityContextRepository contexts, SessionAuthenticationStrategy sessions, RefreshSessions refresh) {
        this.refresh = refresh;
        this.contexts = contexts;
        this.sessions = sessions;
    }

    public void establish(
            Authentication principal, HttpServletRequest request, HttpServletResponse response) {
        var grant = refresh.issue(principal.getName(), request);
        restore(principal, request, response);
        refresh.write(response, grant);
    }

    public void restore(
            Authentication principal, HttpServletRequest request, HttpServletResponse response) {
        sessions.onAuthentication(principal, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(principal);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
    }
}
