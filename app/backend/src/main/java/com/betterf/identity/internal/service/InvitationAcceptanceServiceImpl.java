package com.betterf.identity.internal.service;

import com.betterf.identity.api.dto.*;
import com.betterf.identity.api.dto.IdentityViews.AccountView;
import com.betterf.identity.api.exception.IdentityException;
import com.betterf.identity.api.service.InvitationAcceptanceService;
import com.betterf.identity.internal.entity.*;
import com.betterf.identity.internal.mapper.AccountMapper;
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
    private final OrganizationRepository organizations;
    private final VerificationEmailRepository verificationEmails;
    private final RegistrationLocks locks;
    private final PasswordEncoder passwords;
    private final AccountMapper mapper;
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
    public AccountView accept(MemberRegistrationRequest request) {
        validate(request);
        if (ProfessionalRoles.ALL.stream()
                .noneMatch(role -> role.id().equals(request.professionalRole()))) {
            throw new IdentityException(
                    400, "INVALID_ROLE", "Choose a professional role from the list.");
        }
        var invitation = lockedInvitation(request.invitation().id());
        var preview = inspect(invitation, request.invitation().token());
        if (!"READY".equals(preview.status())) {
            throw new IdentityException(409, preview.status(), preview.message());
        }
        var account = registerMember(invitation, request);
        invitation.setAcceptedAt(clock.instant());
        invitation.setAcceptedTokenHash(invitation.getTokenHash());
        invitation.setTokenHash(null);
        invitation.setStatus("ACCEPTED");
        return mapper.view(account);
    }

    private AccountEntity registerMember(
            InvitationEntity invitation, MemberRegistrationRequest request) {
        var account = accounts.findByEmail(invitation.getEmail()).orElseGet(AccountEntity::new);
        var abandonedOrganization = cancelUnfinishedSignup(account);
        if (account.getId() == null) {
            account.setId(UUID.randomUUID());
        }
        account.setEmail(invitation.getEmail());
        account.setOrganization(invitation.getOrganization());
        account.setFullName(request.fullName().trim());
        account.setProfessionalRole(request.professionalRole());
        account.setPasswordHash(passwords.encode(request.password()));
        account.setStatus(AccountStatus.ACTIVE);
        account.setAccessRole("MEMBER");
        account.setUpdatedAt(clock.instant());
        accounts.saveAndFlush(account);
        if (abandonedOrganization != null) {
            organizations.delete(abandonedOrganization);
        }
        return account;
    }

    /**
     * Deleting the delivery also makes an SMTP attempt already in flight unable to publish a token.
     */
    private OrganizationEntity cancelUnfinishedSignup(AccountEntity account) {
        if (account.getId() == null) {
            return null;
        }
        var pendingOrganization = account.getOrganization();
        verificationEmails.findById(account.getId()).ifPresent(verificationEmails::delete);
        return pendingOrganization;
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
            locks.lock("organization:" + invitation.getOrganization().getId());
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
        var member = accounts.findByEmail(invitation.getEmail()).orElse(null);
        if (member != null
                && (member.getStatus() != AccountStatus.PENDING
                        || member.getAccessRole() != null
                        || member.getOrganization().getStatus() != OrganizationStatus.PENDING)) {
            boolean same =
                    member.getOrganization().getId().equals(invitation.getOrganization().getId());
            return view(
                    invitation,
                    same ? "ALREADY_MEMBER" : "OTHER_COMPANY_MEMBER",
                    same
                            ? "You have already joined this organization. Please log in."
                            : "This email already belongs to another company. Request an invitation"
                                    + " to a different email address.");
        }
        return null;
    }

    private InvitationAcceptanceView checkCapacity(InvitationEntity invitation) {
        if (invitation.getOrganization().getStatus() != OrganizationStatus.ACTIVE) {
            return view(
                    invitation,
                    "INVITATION_UNUSABLE",
                    "This invitation cannot be accepted. Please contact the administrator for a"
                            + " valid invitation.");
        }
        int limit =
                invitation.getOrganization().getMaxActiveAccounts() == null
                        ? configuration.getProperty(
                                "betterf.invitations.max-active-accounts", Integer.class, 10)
                        : invitation.getOrganization().getMaxActiveAccounts();
        if (accounts.countByOrganizationIdAndStatus(
                        invitation.getOrganization().getId(), AccountStatus.ACTIVE)
                >= limit) {
            return view(
                    invitation,
                    "ACCOUNT_LIMIT",
                    "This organization has reached its limit of "
                            + limit
                            + " active accounts. Your invitation cannot be accepted. Please contact"
                            + " the organization administrator.");
        }
        return view(invitation, "READY", "Complete registration to join your organization.");
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
                status, message, invitation.getOrganization().getName(), invitation.getEmail());
    }
}
