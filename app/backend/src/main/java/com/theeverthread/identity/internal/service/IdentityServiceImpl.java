package com.theeverthread.identity.internal.service;

import com.theeverthread.identity.api.dto.*;
import com.theeverthread.identity.api.dto.IdentityViews.*;
import com.theeverthread.identity.api.exception.IdentityException;
import com.theeverthread.identity.api.service.IdentityService;
import com.theeverthread.identity.internal.entity.*;
import com.theeverthread.identity.internal.repository.*;

import jakarta.validation.Validator;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

@Service
@lombok.RequiredArgsConstructor
public class IdentityServiceImpl implements IdentityService {
    private final AccountRepository accounts;
    private final VerificationEmailRepository emails;
    private final RegistrationLocks locks;
    private final PasswordEncoder passwords;
    private final WorkspaceAccess workspaces;
    private final Clock clock;
    private final Validator validator;

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static IdentityException invalidLink() {
        return new IdentityException(
                400,
                "INVALID_VERIFICATION",
                "This link is invalid or has been replaced. Request a new verification email.");
    }

    @Override
    @Transactional
    public PendingView register(RegistrationRequest request) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new IdentityException(
                    400,
                    "INVALID_REQUEST",
                    "Enter your name, a valid email and a password of 10–128 characters with an"
                            + " uppercase letter and a special character.");
        }
        String email = normalizeEmail(request.email());
        locks.lock("email:" + email);
        var existing = accounts.findByEmail(email);
        if (existing.isPresent()) {
            throw emailInUse();
        }
        var account = new AccountEntity();
        account.setId(UUID.randomUUID());
        account.setEmail(email);
        account.setFullName(request.fullName().trim());
        account.setPasswordHash(passwords.encode(request.password()));
        account.setStatus(AccountStatus.PENDING);
        account.setUpdatedAt(clock.instant());
        accounts.save(account);
        var delivery = new VerificationEmailEntity();
        delivery.setAccountId(account.getId());
        delivery.setRecipient(email);
        delivery.setStatus(EmailDeliveryStatus.PENDING);
        delivery.setNextAttemptAt(clock.instant());
        emails.save(delivery);
        return pending(account);
    }

    static IdentityException emailInUse() {
        return new IdentityException(
                409,
                "EMAIL_IN_USE",
                "This email is already registered. Log in with your existing method, or request"
                        + " another verification email if registration is pending.");
    }

    @Override
    @Transactional
    public PendingView resend(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        locks.lock("email:" + email);
        var account =
                accounts.findByEmail(email)
                        .orElseThrow(
                                () ->
                                        new IdentityException(
                                                400,
                                                "REGISTRATION_NOT_FOUND",
                                                "No pending registration was found. Sign up or log"
                                                        + " in if already verified."));
        if (account.getStatus() == AccountStatus.ACTIVE) throw emailInUse();
        var delivery =
                emails.findById(account.getId()).orElseThrow(IdentityServiceImpl::invalidLink);
        if (delivery.getLastSentAt() != null
                && clock.instant().isBefore(delivery.getLastSentAt().plusSeconds(60)))
            throw new IdentityException(
                    429,
                    "RESEND_COOLDOWN",
                    "Wait 60 seconds after the previous email before requesting another.");
        if (delivery.getStatus() == EmailDeliveryStatus.SMTP_ACCEPTED) {
            delivery.setStatus(EmailDeliveryStatus.PENDING);
            delivery.setNextAttemptAt(clock.instant());
            account.setUpdatedAt(clock.instant());
        }
        return pending(account);
    }

    private PendingView pending(AccountEntity account) {
        var delivery =
                emails.findById(account.getId()).orElseThrow(IdentityServiceImpl::invalidLink);
        return new PendingView(
                account.getEmail(),
                delivery.getLastSentAt() == null
                        ? clock.instant()
                        : delivery.getLastSentAt().plusSeconds(60),
                delivery.getStatus().name());
    }

    @Override
    @Transactional(readOnly = true)
    public PendingView deliveryStatus(String rawEmail) {
        return pending(
                accounts.findByEmail(normalizeEmail(rawEmail))
                        .orElseThrow(IdentityServiceImpl::invalidLink));
    }

    @Override
    @Transactional
    public VerificationView verify(UUID id, String token) {
        String email = accounts.emailForId(id).orElseThrow(IdentityServiceImpl::invalidLink);
        locks.lock("email:" + email);
        var account = accounts.findById(id).orElseThrow(IdentityServiceImpl::invalidLink);
        var delivery = emails.findById(id).orElseThrow(IdentityServiceImpl::invalidLink);
        if (token == null
                || !token.matches("[A-Za-z0-9_-]{43}")
                || delivery.getTokenHash() == null
                || !MessageDigest.isEqual(
                        hash(token).getBytes(StandardCharsets.US_ASCII),
                        delivery.getTokenHash().getBytes(StandardCharsets.US_ASCII)))
            throw invalidLink();
        if (!clock.instant().isBefore(delivery.getTokenExpiresAt()))
            throw new IdentityException(
                    400,
                    "EXPIRED_VERIFICATION",
                    "This link has expired. Request a new verification email.");
        if (account.isActive()) return new VerificationView("ALREADY_VERIFIED", null);
        if (account.getStatus() != AccountStatus.PENDING) throw invalidLink();
        account.setStatus(AccountStatus.ACTIVE);
        account.setUpdatedAt(clock.instant());
        workspaces.provisionDefault(account);
        return new VerificationView("VERIFIED", workspaces.view(account, null));
    }

    @Override
    @Transactional(readOnly = true)
    public CredentialsView credentials(String email) {
        return accounts.findByEmail(normalizeEmail(email))
                .map(
                        a ->
                                new CredentialsView(
                                        a.getEmail(), a.getPasswordHash(), "USER", a.isActive()))
                .orElse(null);
    }

    @Override
    @Transactional
    public AccountView current(String email) {
        // Repair older active accounts that predate automatic default assignment.
        // A revoked existing default is never replaced during session recovery.
        locks.lock("email:" + normalizeEmail(email));
        var account = workspaces.account(email);
        workspaces.provisionDefault(account);
        return workspaces.view(account, null);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountView current(String email, UUID workspaceId) {
        var membership = workspaces.require(email, workspaceId, false);
        return workspaces.view(membership.getAccount(), workspaceId);
    }

    static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
