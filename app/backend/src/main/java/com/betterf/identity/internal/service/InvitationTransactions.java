package com.betterf.identity.internal.service;

import com.betterf.identity.api.dto.*;
import com.betterf.identity.api.exception.IdentityException;
import com.betterf.identity.internal.entity.*;
import com.betterf.identity.internal.repository.*;

import jakarta.validation.Validator;

import lombok.RequiredArgsConstructor;

import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** Short transactions on either side of SMTP, with no network operation inside a transaction. */
@Service
@RequiredArgsConstructor
public class InvitationTransactions {
    private final AccountRepository accounts;
    private final InvitationRepository invitations;
    private final RegistrationLocks locks;
    private final Clock clock;
    private final Validator validator;
    private final Environment configuration;
    private final WorkspaceAccess access;
    private final MembershipRepository memberships;

    public record Claim(InvitationView view, String workspaceName, String administratorName) {}

    private MembershipEntity administrator(String email, UUID workspaceId) {
        return access.require(email, workspaceId, true);
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
    public Claim claim(
            String actor, java.util.UUID workspaceId, InvitationRequest request, String token) {
        var admin = administrator(actor, workspaceId);
        String email = recipient(request);
        // Acceptance must acquire these locks in the same order.
        locks.lock("email:" + email);
        locks.lock("workspace:" + admin.getWorkspace().getId());
        requireEligibleRecipient(admin, email);
        var existing =
                invitations
                        .findByWorkspaceIdAndEmail(admin.getWorkspace().getId(), email)
                        .orElse(null);
        if (existing != null && existing.getReplacementTokenHash() != null) {
            throw new IdentityException(
                    409,
                    "RESEND_IN_PROGRESS",
                    "A resend is already in progress. Refresh the sent invitations before"
                            + " retrying.");
        }
        if (existing != null && "ACCEPTED".equals(existing.getStatus())) {
            throw new IdentityException(
                    409, "INVITATION_USED", "This invitation has already been accepted.");
        }
        if (existing != null && "SENDING".equals(existing.getStatus())) {
            return new Claim(view(existing), null, null);
        }
        if (existing != null
                && "SMTP_ACCEPTED".equals(existing.getStatus())
                && clock.instant().isBefore(existing.getExpiresAt())) {
            throw new IdentityException(409, "INVITATION_PENDING", "Invitation already pending");
        }
        requireCapacity(admin);
        var invitation = existing == null ? new InvitationEntity() : existing;
        if (existing == null) {
            invitation.setId(UUID.randomUUID());
        }
        invitation.setWorkspace(admin.getWorkspace());
        invitation.setEmail(email);
        invitation.setAdministratorId(admin.getAccount().getId());
        invitation.setAdministratorName(admin.getAccount().getFullName());
        invitation.setStatus("SENDING");
        invitation.setTokenHash(IdentityServiceImpl.hash(token));
        invitation.setAttemptedAt(clock.instant());
        invitation.setExpiresAt(clock.instant().plus(Duration.ofDays(7)));
        invitation.setSentAt(null);
        invitation.setSmtpMessageId(null);
        invitation.setFailureCode(null);
        invitations.saveAndFlush(invitation);
        return new Claim(
                view(invitation), admin.getWorkspace().getName(), admin.getAccount().getFullName());
    }

    @Transactional
    public InvitationView complete(UUID id, String token, String messageId, String failure) {
        locks.lock("email:" + invitations.emailForId(id).orElseThrow());
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

    private void requireEligibleRecipient(MembershipEntity admin, String email) {
        var recipient = accounts.findByEmail(email);
        if (recipient.isPresent()
                && memberships
                        .findByAccountIdAndWorkspaceIdAndActiveTrue(
                                recipient.get().getId(), admin.getWorkspace().getId())
                        .isPresent()) {
            throw new IdentityException(
                    409, "ALREADY_MEMBER", "Already a member of this workspace.");
        }
    }

    private void requireCapacity(MembershipEntity admin) {
        int defaultLimit =
                configuration.getProperty(
                        "betterf.invitations.max-active-accounts", Integer.class, 10);
        if (defaultLimit < 1) {
            throw new IllegalArgumentException("Active-account cap must be positive");
        }
        int limit =
                admin.getWorkspace().getMaxActiveAccounts() == null
                        ? defaultLimit
                        : admin.getWorkspace().getMaxActiveAccounts();
        if (memberships.countByWorkspaceIdAndActiveTrue(admin.getWorkspace().getId()) >= limit) {
            throw new IdentityException(
                    409,
                    "ACCOUNT_LIMIT",
                    "Your workspace has reached its limit of "
                            + limit
                            + " active accounts. You cannot send invitations.");
        }
    }

    @Transactional(readOnly = true)
    public List<InvitationView> sent(String actor, java.util.UUID workspaceId) {
        var admin = administrator(actor, workspaceId);
        return invitations.managementRecords(admin.getWorkspace().getId(), clock.instant()).stream()
                .map(this::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ActiveMemberView> members(String actor, java.util.UUID workspaceId) {
        var admin = administrator(actor, workspaceId);
        return memberships.findByWorkspaceIdAndActiveTrue(admin.getWorkspace().getId()).stream()
                .filter(m -> m.getAccount().isActive())
                .sorted(
                        java.util.Comparator.comparing(
                                        (MembershipEntity m) -> m.getAccount().getUpdatedAt())
                                .reversed()
                                .thenComparing(m -> m.getAccount().getId()))
                .map(
                        m ->
                                new ActiveMemberView(
                                        m.getAccount().getFullName(),
                                        m.getAccount().getEmail(),
                                        m.getRole()))
                .toList();
    }

    /** Reserves a replacement without changing the current link or its acceptance state. */
    @Transactional
    public Claim claimResend(String actor, java.util.UUID workspaceId, UUID id, String token) {
        var admin = administrator(actor, workspaceId);
        String email = invitations.emailForId(id).orElseThrow(this::notFound);
        locks.lock("email:" + email);
        locks.lock("workspace:" + admin.getWorkspace().getId());
        var invitation = invitations.findById(id).orElseThrow(this::notFound);
        if (!invitation.getWorkspace().getId().equals(admin.getWorkspace().getId())) {
            throw notFound();
        }
        if (!("FAILED".equals(invitation.getStatus())
                || ("SMTP_ACCEPTED".equals(invitation.getStatus())
                        && clock.instant().isBefore(invitation.getExpiresAt())))) {
            throw new IdentityException(
                    409, "INVITATION_UNUSABLE", "This invitation cannot be resent.");
        }
        requireEligibleRecipient(admin, email);
        requireCapacity(admin);
        if (invitation.getReplacementTokenHash() != null) {
            throw new IdentityException(
                    409,
                    "RESEND_IN_PROGRESS",
                    "A resend is already in progress. Refresh the sent invitations before"
                            + " retrying.");
        }
        if (clock.instant().isBefore(resendAvailableAt(invitation))) {
            throw new IdentityException(
                    409, "RESEND_COOLDOWN", "Please wait 60 seconds between invitation sends.");
        }
        invitation.setReplacementTokenHash(IdentityServiceImpl.hash(token));
        invitation.setReplacementAttemptedAt(clock.instant());
        invitation.setReplacementExpiresAt(clock.instant().plus(Duration.ofDays(7)));
        var current = view(invitation);
        var replacement =
                new InvitationView(
                        current.id(),
                        current.email(),
                        current.status(),
                        invitation.getReplacementExpiresAt(),
                        current.message());
        return new Claim(
                replacement, admin.getWorkspace().getName(), admin.getAccount().getFullName());
    }

    /** Serializes promotion with acceptance; an accepted invitation can never be reopened. */
    @Transactional
    public InvitationView completeResend(UUID id, String token, String messageId, String failure) {
        locks.lock("email:" + invitations.emailForId(id).orElseThrow(this::notFound));
        var invitation = invitations.findById(id).orElseThrow(this::notFound);
        locks.lock("workspace:" + invitation.getWorkspace().getId());
        if (!IdentityServiceImpl.hash(token).equals(invitation.getReplacementTokenHash())) {
            throw new IllegalStateException("Invitation replacement changed");
        }
        if (!"ACCEPTED".equals(invitation.getStatus()) && failure == null) {
            invitation.setTokenHash(invitation.getReplacementTokenHash());
            invitation.setExpiresAt(invitation.getReplacementExpiresAt());
            invitation.setSentAt(clock.instant());
            invitation.setSmtpMessageId(messageId);
            invitation.setStatus("SMTP_ACCEPTED");
        }
        invitation.setReplacementTokenHash(null);
        invitation.setReplacementExpiresAt(null);
        invitation.setFailureCode(failure);
        var result = view(invitation);
        String message =
                "ACCEPTED".equals(invitation.getStatus())
                        ? "This invitation was accepted while the resend was in progress."
                        : failure == null
                                ? "Invitation resent successfully."
                                : "The replacement could not be confirmed. The previous link and"
                                        + " expiry are unchanged. Check the recipient address and"
                                        + " retry after 60 seconds.";
        return new InvitationView(
                result.id(),
                result.email(),
                result.status(),
                result.expiresAt(),
                message,
                result.sentAt(),
                result.resendAvailableAt(),
                result.resending(),
                failure != null);
    }

    private IdentityException notFound() {
        return new IdentityException(
                404, "INVITATION_NOT_FOUND", "No invitation was found in your workspace.");
    }

    private Instant resendAvailableAt(InvitationEntity invitation) {
        Instant latest = invitation.getAttemptedAt();
        if (invitation.getSentAt() != null && invitation.getSentAt().isAfter(latest)) {
            latest = invitation.getSentAt();
        }
        if (invitation.getReplacementAttemptedAt() != null
                && invitation.getReplacementAttemptedAt().isAfter(latest)) {
            latest = invitation.getReplacementAttemptedAt();
        }
        return latest.plusSeconds(60);
    }

    @Transactional(readOnly = true)
    public InvitationPageView history(String actor, java.util.UUID workspaceId, int page) {
        var admin = administrator(actor, workspaceId);
        if (page < 0 || page > Integer.MAX_VALUE / 25) {
            throw new IdentityException(400, "INVALID_REQUEST", "Choose a valid invitation page.");
        }
        var invitationsPage =
                invitations.findByWorkspaceId(
                        admin.getWorkspace().getId(),
                        PageRequest.of(
                                page, 25, Sort.by(Sort.Direction.DESC, "attemptedAt", "id")));
        return new InvitationPageView(
                invitationsPage.getContent().stream().map(this::view).toList(),
                invitationsPage.hasNext());
    }

    @Transactional(readOnly = true)
    public InvitationView status(
            String actor, java.util.UUID workspaceId, InvitationRequest request) {
        var admin = administrator(actor, workspaceId);
        return invitations
                .findByWorkspaceIdAndEmail(admin.getWorkspace().getId(), recipient(request))
                .map(this::view)
                .orElseThrow(
                        () ->
                                new IdentityException(
                                        404,
                                        "INVITATION_NOT_FOUND",
                                        "No invitation was found. You can retry sending."));
    }

    private InvitationView view(InvitationEntity invitation) {
        String message =
                switch (invitation.getStatus()) {
                    case "SMTP_ACCEPTED" ->
                            "Invitation sent. The email service accepted it for delivery.";
                    case "FAILED" -> "The email could not be sent. Check the address and retry.";
                    case "SENDING" -> "Sending invitation. Please wait.";
                    default -> "Invitation is no longer pending.";
                };
        return new InvitationView(
                invitation.getId(),
                invitation.getEmail(),
                invitation.getStatus(),
                invitation.getExpiresAt(),
                message,
                invitation.getSentAt(),
                resendAvailableAt(invitation),
                invitation.getReplacementTokenHash() != null,
                invitation.getFailureCode() != null);
    }
}
