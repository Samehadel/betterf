package com.theeverthread.identity.internal.service;

import com.theeverthread.identity.api.dto.IdentityViews.AccountView;
import com.theeverthread.identity.api.dto.SocialIdentityView;
import com.theeverthread.identity.api.exception.IdentityException;
import com.theeverthread.identity.api.service.SocialIdentityService;
import com.theeverthread.identity.internal.entity.*;
import com.theeverthread.identity.internal.repository.*;

import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** Serializes provider and email claims; new users and links commit together. */
@Service
@RequiredArgsConstructor
public class SocialIdentityServiceImpl implements SocialIdentityService {
    private final AccountRepository accounts;
    private final OAuthIdentityRepository providers;
    private final RegistrationLocks locks;
    private final WorkspaceAccess workspaces;
    private final Validator validator;
    private final Clock clock;

    private record EmailValue(@NotBlank @Email @Size(max = 254) String value) {}

    @Override
    @Transactional
    public AccountView authenticate(SocialIdentityView identity) {
        String email = validate(identity);
        lock(identity, email);
        var linked = providers.findByProviderAndSubjectId(identity.provider(), identity.subject());
        if (linked.isPresent()) {
            return active(linked.get().getAccount());
        }
        if (accounts.findByEmail(email).isPresent()) {
            throw new IdentityException(
                    409,
                    "LINK_REQUIRED",
                    "Log in using your existing method, then confirm linking this provider.");
        }
        throw new IdentityException(
                409, "SIGNUP_REQUIRED", "Create an account using email and password first.");
    }

    @Override
    @Transactional
    public AccountView link(String authenticatedEmail, SocialIdentityView identity) {
        String email = validate(identity);
        if (!email.equals(IdentityServiceImpl.normalizeEmail(authenticatedEmail))) {
            throw new IdentityException(
                    403,
                    "LINK_ACCOUNT_MISMATCH",
                    "Log in to the account using the email returned by the provider.");
        }
        lock(identity, email);
        var account = accounts.findByEmail(email).orElseThrow(IdentityServiceImpl::emailInUse);
        var view = active(account);
        var linked = providers.findByProviderAndSubjectId(identity.provider(), identity.subject());
        if (linked.isPresent()) {
            if (!linked.get().getAccount().getId().equals(account.getId())) {
                throw new IdentityException(
                        409,
                        "PROVIDER_ALREADY_LINKED",
                        "This provider login is already linked to an account. Use its existing"
                                + " login method.");
            }
            return view;
        }
        attach(account, identity);
        return view;
    }

    private String validate(SocialIdentityView identity) {
        if (identity == null
                || !("linkedin".equals(identity.provider())
                        || "facebook".equals(identity.provider()))
                || identity.subject() == null
                || identity.subject().isBlank()
                || identity.subject().length() > 255) {
            throw new IdentityException(
                    400,
                    "PROVIDER_AUTHENTICATION_FAILED",
                    "Provider authentication failed. Please try again.");
        }
        if (Boolean.FALSE.equals(identity.emailVerified())) {
            throw new IdentityException(
                    400,
                    "PROVIDER_EMAIL_UNVERIFIED",
                    "Verify your email with the provider, then try again.");
        }
        String email =
                identity.email() == null
                        ? ""
                        : IdentityServiceImpl.normalizeEmail(identity.email());
        if (!validator.validate(new EmailValue(email)).isEmpty()) {
            throw new IdentityException(
                    400,
                    "PROVIDER_EMAIL_MISSING",
                    "Allow the provider to share a valid email, or sign up using email and"
                            + " password.");
        }
        return email;
    }

    private void lock(SocialIdentityView identity, String email) {
        locks.lock("provider:" + identity.provider() + ":" + identity.subject());
        locks.lock("email:" + email);
    }

    private AccountView active(AccountEntity account) {
        if (!account.isActive()) {
            throw new IdentityException(
                    403,
                    "ACCOUNT_INACTIVE",
                    "Verify your email and log in using your existing method.");
        }
        return workspaces.view(account, null);
    }

    private void attach(AccountEntity account, SocialIdentityView identity) {
        var provider = new OAuthIdentityEntity();
        provider.setId(UUID.randomUUID());
        provider.setAccount(account);
        provider.setProvider(identity.provider());
        provider.setSubjectId(identity.subject());
        providers.saveAndFlush(provider);
    }
}
