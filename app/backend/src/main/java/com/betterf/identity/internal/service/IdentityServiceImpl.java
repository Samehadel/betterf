package com.betterf.identity.internal.service;

import com.betterf.identity.api.dto.*;
import com.betterf.identity.api.dto.IdentityViews.*;
import com.betterf.identity.api.exception.IdentityException;
import com.betterf.identity.api.service.IdentityService;
import com.betterf.identity.internal.entity.*;
import com.betterf.identity.internal.mapper.AccountMapper;
import com.betterf.identity.internal.repository.*;

import jakarta.validation.Validator;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

@Service
public class IdentityServiceImpl implements IdentityService {
    private final AccountRepository accounts;
    private final OrganizationRepository organizations;
    private final VerificationEmailRepository emails;
    private final RegistrationLocks locks;
    private final PasswordEncoder passwords;
    private final AccountMapper mapper;
    private final Clock clock;
    private final Validator validator;

    public IdentityServiceImpl(
            AccountRepository accounts,
            OrganizationRepository organizations,
            RegistrationLocks locks,
            PasswordEncoder passwords,
            AccountMapper mapper,
            Clock clock,
            Validator validator,
            VerificationEmailRepository emails) {
        this.accounts = accounts;
        this.organizations = organizations;
        this.locks = locks;
        this.passwords = passwords;
        this.mapper = mapper;
        this.clock = clock;
        this.validator = validator;
        this.emails = emails;
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static IdentityException duplicate() {
        return new IdentityException(
                409,
                "DUPLICATE_REGISTRATION",
                "You cannot register this company. It is already registered.");
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
        var violations = validator.validate(request);
        if (!violations.isEmpty())
            throw new IdentityException(
                    400, "INVALID_REQUEST", violations.iterator().next().getMessage());
        String domain = RootWebsite.domain(request.website());
        if (ProfessionalRoles.ALL.stream()
                .noneMatch(role -> role.id().equals(request.professionalRole())))
            throw new IdentityException(
                    400, "INVALID_ROLE", "Choose a professional role from the list.");
        String email = normalizeEmail(request.email());
        locks.lock("email:" + email);
        if (organizations.existsByDomainAndStatus(domain, OrganizationStatus.ACTIVE))
            throw duplicate();
        var existing = accounts.findByEmail(email);
        if (existing.isPresent()) {
            var account = existing.get();
            if (account.getStatus() == AccountStatus.ACTIVE
                    || account.getOrganization().getStatus() == OrganizationStatus.ACTIVE)
                throw duplicate();
            // A repeated public submission must never overwrite another person's password or
            // profile.
            return pending(account);
        }
        var account = new AccountEntity();
        account.setId(UUID.randomUUID());
        account.setEmail(email);
        account.setFullName(request.fullName().trim());
        account.setPasswordHash(passwords.encode(request.password()));
        account.setProfessionalRole(request.professionalRole());
        account.setStatus(AccountStatus.PENDING);
        account.setUpdatedAt(clock.instant());
        var organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName(request.companyName().trim());
        organization.setDomain(domain);
        organization.setSpecialization(request.specialization().trim());
        organization.setStatus(OrganizationStatus.PENDING);
        organization = organizations.save(organization);
        account.setOrganization(organization);
        account = accounts.save(account);
        var delivery = new VerificationEmailEntity();
        delivery.setAccountId(account.getId());
        delivery.setRecipient(email);
        delivery.setStatus(EmailDeliveryStatus.PENDING);
        delivery.setNextAttemptAt(clock.instant());
        emails.save(delivery);
        return pending(account);
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
                                                "No pending registration was found. Register your"
                                                        + " organization or log in if already"
                                                        + " verified."));
        if (account.getStatus() == AccountStatus.ACTIVE
                || account.getOrganization().getStatus() == OrganizationStatus.ACTIVE)
            throw duplicate();
        var delivery =
                emails.findById(account.getId()).orElseThrow(IdentityServiceImpl::invalidLink);
        if (delivery.getLastSentAt() != null
                && clock.instant().isBefore(delivery.getLastSentAt().plusSeconds(60)))
            throw new IdentityException(
                    429,
                    "RESEND_COOLDOWN",
                    "Wait 60 seconds after the previous email before requesting another.");
        if (organizations.existsByDomainAndStatus(
                account.getOrganization().getDomain(), OrganizationStatus.ACTIVE))
            throw duplicate();
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
        if (account.getStatus() == AccountStatus.ACTIVE
                && account.getOrganization().getStatus() == OrganizationStatus.ACTIVE)
            return new VerificationView("ALREADY_VERIFIED");
        if (account.getStatus() != AccountStatus.PENDING
                || account.getOrganization().getStatus() != OrganizationStatus.PENDING)
            throw duplicate();
        locks.lock("domain:" + account.getOrganization().getDomain());
        if (organizations.existsByDomainAndStatus(
                account.getOrganization().getDomain(), OrganizationStatus.ACTIVE))
            throw duplicate();
        account.getOrganization().setStatus(OrganizationStatus.ACTIVE);
        account.setAccessRole("ADMIN");
        account.setStatus(AccountStatus.ACTIVE);
        account.setUpdatedAt(clock.instant());
        return new VerificationView("VERIFIED");
    }

    @Override
    @Transactional(readOnly = true)
    public CredentialsView credentials(String email) {
        return accounts.findByEmail(normalizeEmail(email))
                .map(
                        a ->
                                new CredentialsView(
                                        a.getEmail(),
                                        a.getPasswordHash(),
                                        a.getAccessRole(),
                                        a.getStatus() == AccountStatus.ACTIVE
                                                && a.getOrganization().getStatus()
                                                        == OrganizationStatus.ACTIVE))
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountView current(String email) {
        var account =
                accounts.findByEmail(normalizeEmail(email))
                        .filter(
                                a ->
                                        a.getStatus() == AccountStatus.ACTIVE
                                                && a.getOrganization().getStatus()
                                                        == OrganizationStatus.ACTIVE)
                        .orElseThrow(
                                () ->
                                        new IdentityException(
                                                403,
                                                "ACCESS_DENIED",
                                                "Verify your email and log in to continue."));
        return mapper.view(account);
    }

    @Override
    public List<RoleView> roles() {
        return ProfessionalRoles.ALL;
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
