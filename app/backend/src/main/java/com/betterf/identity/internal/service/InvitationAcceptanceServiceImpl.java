package com.betterf.identity.internal.service;

import com.betterf.identity.api.dto.*;
import com.betterf.identity.api.dto.IdentityViews.AccountView;
import com.betterf.identity.api.exception.IdentityException;
import com.betterf.identity.api.service.InvitationAcceptanceService;
import com.betterf.identity.internal.entity.*;
import com.betterf.identity.internal.repository.*;

import jakarta.validation.Validator;

import lombok.RequiredArgsConstructor;

import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Serializes acceptance with registration, verification, invitation sends, and other acceptances.
 */
@Service
@RequiredArgsConstructor
public class InvitationAcceptanceServiceImpl implements InvitationAcceptanceService {
    private final InvitationRepository invitations;
    private final AccountRepository accounts;
    private final WorkspaceAccess workspaces;
    private final MembershipRepository memberships;
    private final VerificationEmailRepository verificationEmails;
    private final RegistrationLocks locks;
    private final PasswordEncoder passwords;
    private final Validator validator;
    private final Clock clock;

    private final Environment configuration;

    @Override
    @Transactional
    public InvitationAcceptanceView preview(InvitationLinkRequest request) {
        validate(request);
        var invitation = lockedInvitation(request.id());
        return inspect(invitation, request.token());
    }

    @Override
    @Transactional
    public AccountView accept(MemberRegistrationRequest request, String authenticatedEmail) {
        validate(request);
        var invitation = lockedInvitation(request.invitation().id());
        var preview = inspect(invitation, request.invitation().token());
        if (!"READY".equals(preview.status())) {
            throw new IdentityException(409, preview.status(), preview.message());
        }
        var account = registerMember(invitation, request, authenticatedEmail);
        invitation.setAcceptedAt(clock.instant());
        invitation.setAcceptedTokenHash(invitation.getTokenHash());
        invitation.setTokenHash(null);
        invitation.setStatus("ACCEPTED");
        return workspaces.view(account, invitation.getWorkspace().getId());
    }

    private AccountEntity registerMember(
            InvitationEntity invitation,
            MemberRegistrationRequest request,
            String authenticatedEmail) {
        if (authenticatedEmail != null
                && !IdentityServiceImpl.normalizeEmail(authenticatedEmail)
                        .equals(invitation.getEmail())) {
            throw new IdentityException(
                    409, "ACCOUNT_MISMATCH", "Switch to the invited account before accepting.");
        }
        var account = accounts.findByEmail(invitation.getEmail()).orElse(null);
        if (account == null) {
            var registration =
                    new RegistrationRequest(
                            request.fullName(), invitation.getEmail(), request.password());
            validate(registration);
            account = new AccountEntity();
            account.setId(UUID.randomUUID());
            account.setEmail(invitation.getEmail());
            account.setFullName(request.fullName().trim());
            account.setPasswordHash(passwords.encode(request.password()));
        } else if (account.isActive()) {
            if (authenticatedEmail == null)
                throw new IdentityException(
                        401,
                        "SIGN_IN_REQUIRED",
                        "Sign in to your existing account before accepting.");
        } else {
            // Possession of the invitation verifies the email, but never authorizes replacing
            // credentials.
            if (request.password() == null
                    || !passwords.matches(request.password(), account.getPasswordHash())) {
                throw new IdentityException(
                        401,
                        "SIGN_IN_REQUIRED",
                        "Use the password from your pending signup to accept this invitation.");
            }
            verificationEmails.findById(account.getId()).ifPresent(verificationEmails::delete);
        }
        account.setStatus(AccountStatus.ACTIVE);
        account.setUpdatedAt(clock.instant());
        account = accounts.save(account);
        workspaces.provisionDefault(account);
        workspaces.join(account, invitation.getWorkspace(), "MEMBER");
        return account;
    }

    private void validate(Object request) {
        if (request == null) {
            throw new IdentityException(
                    400, "INVALID_REQUEST", "Registration details are required.");
        }
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new IdentityException(
                    400, "INVALID_REQUEST", violations.iterator().next().getMessage());
        }
    }

    private InvitationEntity lockedInvitation(UUID id) {
        var email = invitations.emailForId(id).orElse(null);
        if (email == null) {
            return null;
        }
        locks.lock("email:" + email);
        var invitation = invitations.findById(id).orElse(null);
        if (invitation != null) {
            locks.lock("workspace:" + invitation.getWorkspace().getId());
        }
        return invitation;
    }

    private InvitationAcceptanceView inspect(InvitationEntity invitation, String token) {
        String hash = IdentityServiceImpl.hash(token);
        if (invitation == null) {
            return invalid();
        }
        if (hash.equals(invitation.getAcceptedTokenHash())) {
            return view(
                    invitation,
                    "INVITATION_USED",
                    "This invitation link has already been used and the account was activated.");
        }
        if (!hash.equals(invitation.getTokenHash())) {
            return invalid();
        }
        if (!"SMTP_ACCEPTED".equals(invitation.getStatus())
                || !clock.instant().isBefore(invitation.getExpiresAt())) {
            return view(
                    invitation,
                    "INVITATION_UNUSABLE",
                    "This invitation has expired, been revoked, or been replaced. Please contact"
                            + " the administrator for a valid invitation.");
        }
        var membershipFailure = membershipFailure(invitation);
        return membershipFailure != null ? membershipFailure : checkCapacity(invitation);
    }

    private InvitationAcceptanceView membershipFailure(InvitationEntity invitation) {
        var account = accounts.findByEmail(invitation.getEmail()).orElse(null);
        if (account != null
                && memberships
                        .findByAccountIdAndWorkspaceIdAndActiveTrue(
                                account.getId(), invitation.getWorkspace().getId())
                        .isPresent()) {
            return view(
                    invitation,
                    "ALREADY_MEMBER",
                    "You already belong to this workspace. Sign in to open it.");
        }
        return null;
    }

    private InvitationAcceptanceView checkCapacity(InvitationEntity invitation) {
        if (invitation.getWorkspace().getStatus() != WorkspaceStatus.ACTIVE) {
            return view(
                    invitation,
                    "INVITATION_UNUSABLE",
                    "This invitation cannot be accepted. Please contact the administrator for a"
                            + " valid invitation.");
        }
        int limit =
                invitation.getWorkspace().getMaxActiveAccounts() == null
                        ? configuration.getProperty(
                                "betterf.invitations.max-active-accounts", Integer.class, 10)
                        : invitation.getWorkspace().getMaxActiveAccounts();
        if (memberships.countByWorkspaceIdAndActiveTrue(invitation.getWorkspace().getId())
                >= limit) {
            return view(
                    invitation,
                    "ACCOUNT_LIMIT",
                    "This workspace has reached its limit of "
                            + limit
                            + " active accounts. Your invitation cannot be accepted. Please contact"
                            + " the workspace administrator.");
        }
        return view(invitation, "READY", "Complete registration to join your workspace.");
    }

    private InvitationAcceptanceView invalid() {
        return new InvitationAcceptanceView(
                "INVALID_INVITATION",
                "This invitation is invalid or has been replaced. Please contact the administrator"
                        + " for a valid invitation.",
                null,
                null);
    }

    private InvitationAcceptanceView view(
            InvitationEntity invitation, String status, String message) {
        return new InvitationAcceptanceView(
                status, message, invitation.getWorkspace().getName(), invitation.getEmail());
    }
}
