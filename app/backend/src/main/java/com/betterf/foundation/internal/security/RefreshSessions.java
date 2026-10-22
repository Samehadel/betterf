package com.betterf.foundation.internal.security;

import com.betterf.identity.api.service.IdentityService;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefreshSessions {
    private static final String COOKIE = "BETTERF_REFRESH";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final RefreshSessionRepository repository;
    private final IdentityService identity;
    private final Clock clock;
    @Value("${betterf.auth.refresh-lifetime:14d}") private Duration lifetime;
    @Value("${server.servlet.session.cookie.secure:true}") private boolean secure;

    public record Grant(String email, String role, String token, Instant expiresAt) {}
    private record Token(UUID id, String hash) {}

    @Transactional
    public Grant issue(String email, HttpServletRequest request) {
        revoke(request);
        var credentials = identity.credentials(email);
        if (credentials == null || !credentials.active()) throw new IllegalStateException("Inactive account");
        var session = new RefreshSession();
        session.id = UUID.randomUUID();
        session.email = email;
        session.credentialHash = hash(credentials.passwordHash());
        if (lifetime.isNegative() || lifetime.isZero()) throw new IllegalStateException("Invalid refresh lifetime");
        session.expiresAt = clock.instant().plus(lifetime);
        var grant = rotate(session, credentials.accessRole());
        repository.save(session);
        return grant;
    }

    @Transactional
    public Grant consume(HttpServletRequest request) {
        var token = read(request);
        if (token == null) return null;
        var session = repository.lock(token.id()).orElse(null);
        if (session == null) return null;
        if (!session.tokenHash.equals(token.hash())) {
            // A replay of the preceding token revokes this browser's refresh session.
            if (token.hash().equals(session.previousHash)) repository.delete(session);
            return null;
        }
        var credentials = identity.credentials(session.email);
        if (!session.expiresAt.isAfter(clock.instant()) || credentials == null
                || !credentials.active() || !session.credentialHash.equals(hash(credentials.passwordHash()))) {
            repository.delete(session);
            return null;
        }
        return rotate(session, credentials.accessRole());
    }

    @Transactional
    public void revoke(HttpServletRequest request) {
        var token = read(request);
        if (token == null) return;
        repository.lock(token.id()).ifPresent(session -> {
            if (token.hash().equals(session.tokenHash) || token.hash().equals(session.previousHash)) {
                repository.delete(session);
            }
        });
    }

    @Scheduled(fixedDelay = 3600000)
    @Transactional
    public void cleanup() { repository.deleteExpired(clock.instant()); }

    public void write(HttpServletResponse response, Grant grant) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, grant == null ? "" : grant.token())
                .httpOnly(true).secure(secure).sameSite("Strict").path("/api/auth")
                .maxAge(grant == null ? Duration.ZERO : Duration.between(clock.instant(), grant.expiresAt()))
                .build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    private Grant rotate(RefreshSession session, String role) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        session.previousHash = session.tokenHash;
        session.tokenHash = hash(secret);
        return new Grant(session.email, role, session.id + "." + secret, session.expiresAt);
    }

    private Token read(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (var cookie : request.getCookies()) {
            if (!COOKIE.equals(cookie.getName())) continue;
            String value = cookie.getValue();
            if (!value.matches("[0-9a-f-]{36}\\.[A-Za-z0-9_-]{43}")) return null;
            try { return new Token(UUID.fromString(value.substring(0, 36)), hash(value.substring(37))); }
            catch (IllegalArgumentException ignored) { return null; }
        }
        return null;
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
