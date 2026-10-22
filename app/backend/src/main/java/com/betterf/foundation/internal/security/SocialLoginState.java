package com.betterf.foundation.internal.security;

import com.betterf.identity.api.dto.SocialIdentityView;
import com.betterf.identity.api.exception.IdentityException;

import jakarta.servlet.http.HttpServletRequest;

import java.io.Serializable;
import java.time.Instant;

/**
 * Session-bound, short-lived linking challenge; refresh and verification never satisfy
 * reauthentication.
 */
public final class SocialLoginState {
    private static final String PENDING = SocialLoginState.class.getName() + ".pending";
    private static final String TRUSTED = SocialLoginState.class.getName() + ".trusted";

    private SocialLoginState() {}

    public record Pending(SocialIdentityView identity, Instant createdAt) implements Serializable {}

    private record Trusted(String email, Instant at) implements Serializable {}

    public static void pending(
            HttpServletRequest request, SocialIdentityView identity, Instant now) {
        request.getSession().setAttribute(PENDING, new Pending(identity, now));
        request.getSession().removeAttribute(TRUSTED);
    }

    public static void authenticated(HttpServletRequest request, String email, Instant now) {
        request.getSession().setAttribute(TRUSTED, new Trusted(email, now));
    }

    public static Pending pending(HttpServletRequest request, Instant now) {
        var session = request.getSession(false);
        var value = session == null ? null : session.getAttribute(PENDING);
        if (value instanceof Pending pending && now.isBefore(pending.createdAt().plusSeconds(300)))
            return pending;
        return null;
    }

    public static SocialIdentityView requireLink(
            HttpServletRequest request, String email, Instant now) {
        var pending = pending(request, now);
        var session = request.getSession(false);
        var trusted = session == null ? null : session.getAttribute(TRUSTED);
        if (pending == null
                || !(trusted instanceof Trusted proof)
                || !proof.email().equals(email)
                || proof.at().isBefore(pending.createdAt())
                || !now.isBefore(proof.at().plusSeconds(300))) {
            throw new IdentityException(
                    401,
                    "LINK_REAUTHENTICATION_REQUIRED",
                    "Restart provider login, then sign in with your existing method and confirm the"
                        + " link within five minutes.");
        }
        return pending.identity();
    }

    public static void clear(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.removeAttribute(PENDING);
    }
}
