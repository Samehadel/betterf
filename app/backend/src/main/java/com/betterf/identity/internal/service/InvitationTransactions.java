package com.betterf.identity.internal.service;

import com.betterf.identity.api.dto.*;
import com.betterf.identity.api.exception.IdentityException;
import com.betterf.identity.internal.entity.*;
import com.betterf.identity.internal.repository.*;

import jakarta.validation.Validator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** Short transactions on either side of SMTP, with no network operation inside a transaction. */
@Service
public class InvitationTransactions {
    private final AccountRepository accounts;
    private final InvitationRepository invitations;
    private final RegistrationLocks locks;
    private final Clock clock;
    private final Validator validator;
    private final int defaultLimit;

    public InvitationTransactions(
            AccountRepository accounts,
            InvitationRepository invitations,
            RegistrationLocks locks,
            Clock clock,
            Validator validator,
            @Value("${betterf.invitations.max-active-accounts:10}") int defaultLimit) {
        if (defaultLimit < 1) {
            throw new IllegalArgumentException("Active-account cap must be positive");
        }
        this.accounts = accounts;
        this.invitations = invitations;
        this.locks = locks;
        this.clock = clock;
        this.validator = validator;
        this.defaultLimit = defaultLimit;
    }

    public record Claim(InvitationView view, String companyName, String administratorName) {}

    private AccountEntity administrator(String email) {
        return accounts.findByEmail(IdentityServiceImpl.normalizeEmail(email))
                .filter(a -> a.getStatus() == AccountStatus.ACTIVE
                        && a.getOrganization().getStatus() == OrganizationStatus.ACTIVE
                        && "ADMIN".equals(a.getAccessRole()))
                .orElseThrow(() -> new IdentityException(
                        403, "ACCESS_DENIED",
                        "Only a verified administrator can invite team members."));
    }

    private String recipient(InvitationRequest request) {
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new IdentityException(
                    400, "INVALID_REQUEST", violations.iterator().next().getMessage());
        }
        return IdentityServiceImpl.normalizeEmail(request.email());
    }

    @Transactional
    public Claim claim(String actor, InvitationRequest request, String token) {
        var admin = administrator(actor);
        String email = recipient(request);
        // Acceptance must acquire these locks in the same order.
        locks.lock("email:" + email);
        locks.lock("organization:" + admin.getOrganization().getId());
        var member = accounts.findByEmail(email)
                .filter(a -> a.getAccessRole() != null
                        || a.getStatus() == AccountStatus.ACTIVE
                        || a.getOrganization().getStatus() == OrganizationStatus.ACTIVE);
        if (member.isPresent()) {
            boolean same = member.get().getOrganization().getId()
                    .equals(admin.getOrganization().getId());
            throw new IdentityException(
                    409,
                    same ? "ALREADY_MEMBER" : "OTHER_COMPANY_MEMBER",
                    same ? "Already a member."
                            : "This email already belongs to another company. Use a different email address.");
        }
        var existing = invitations.findByOrganizationIdAndEmail(
                admin.getOrganization().getId(), email).orElse(null);
        if (existing != null && "SENDING".equals(existing.getStatus())) {
            return new Claim(view(existing), null, null);
        }
        if (existing != null && "SMTP_ACCEPTED".equals(existing.getStatus())
                && clock.instant().isBefore(existing.getExpiresAt())) {
            throw new IdentityException(409, "INVITATION_PENDING", "Invitation already pending");
        }
        int limit = admin.getOrganization().getMaxActiveAccounts() == null
                ? defaultLimit : admin.getOrganization().getMaxActiveAccounts();
        if (accounts.countByOrganizationIdAndStatus(
                admin.getOrganization().getId(), AccountStatus.ACTIVE) >= limit) {
            throw new IdentityException(
                    409, "ACCOUNT_LIMIT",
                    "Your organization has reached its limit of " + limit
                            + " active accounts. You cannot send invitations.");
        }
        var invitation = existing == null ? new InvitationEntity() : existing;
        if (existing == null) {
            invitation.setId(UUID.randomUUID());
        }
        invitation.setOrganization(admin.getOrganization());
        invitation.setEmail(email);
        invitation.setAdministratorId(admin.getId());
        invitation.setAdministratorName(admin.getFullName());
        invitation.setStatus("SENDING");
        invitation.setTokenHash(IdentityServiceImpl.hash(token));
        invitation.setAttemptedAt(clock.instant());
        invitation.setExpiresAt(clock.instant().plus(Duration.ofDays(7)));
        invitation.setSentAt(null);
        invitation.setSmtpMessageId(null);
        invitation.setFailureCode(null);
        invitations.saveAndFlush(invitation);
        return new Claim(view(invitation), admin.getOrganization().getName(), admin.getFullName());
    }

    @Transactional
    public InvitationView complete(UUID id, String token, String messageId, String failure) {
        var preliminary = invitations.findById(id).orElseThrow();
        locks.lock("email:" + preliminary.getEmail());
        var invitation = invitations.findById(id).orElseThrow();
        if (!"SENDING".equals(invitation.getStatus())
                || !IdentityServiceImpl.hash(token).equals(invitation.getTokenHash())) {
            throw new IllegalStateException("Invitation attempt changed");
        }
        invitation.setStatus(failure == null ? "SMTP_ACCEPTED" : "FAILED");
        invitation.setFailureCode(failure);
        invitation.setSmtpMessageId(messageId);
        if (failure == null) {
            invitation.setSentAt(clock.instant());
        } else {
            invitation.setTokenHash(null);
        }
        return view(invitation);
    }

    @Transactional(readOnly = true)
    public InvitationView status(String actor, InvitationRequest request) {
        var admin = administrator(actor);
        return invitations.findByOrganizationIdAndEmail(
                admin.getOrganization().getId(), recipient(request))
                .map(this::view)
                .orElseThrow(() -> new IdentityException(
                        404, "INVITATION_NOT_FOUND", "No invitation was found. You can retry sending."));
    }

    private InvitationView view(InvitationEntity invitation) {
        String message = switch (invitation.getStatus()) {
            case "SMTP_ACCEPTED" -> "Invitation sent. The email service accepted it for delivery.";
            case "FAILED" -> "The email could not be sent. Check the address and retry.";
            case "SENDING" -> "Sending invitation. Please wait.";
            default -> "Invitation is no longer pending.";
        };
        return new InvitationView(
                invitation.getId(), invitation.getEmail(), invitation.getStatus(),
                invitation.getExpiresAt(), message);
    }
}
