package com.betterf.identity.internal.service;

import com.betterf.identity.internal.entity.*;
import com.betterf.identity.internal.repository.*;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** Short transactions around SMTP: claim is committed before any network operation. */
@Service
public class VerificationEmailTransactions {
    private final VerificationEmailRepository emails;
    private final AccountRepository accounts;
    private final OrganizationRepository organizations;
    private final RegistrationLocks locks;
    private final Clock clock;

    public VerificationEmailTransactions(
            VerificationEmailRepository emails,
            AccountRepository accounts,
            OrganizationRepository organizations,
            RegistrationLocks locks,
            Clock clock) {
        this.emails = emails;
        this.accounts = accounts;
        this.organizations = organizations;
        this.locks = locks;
        this.clock = clock;
    }

    public record Attempt(UUID accountId, String recipient, UUID attemptId) {}

    @Transactional(readOnly = true)
    public List<UUID> due() {
        return emails.due(
                List.of(EmailDeliveryStatus.PENDING, EmailDeliveryStatus.FAILED),
                clock.instant(),
                PageRequest.of(0, 25));
    }

    @Transactional
    public Attempt claim(UUID id) {
        var email = accounts.emailForId(id);
        if (email.isEmpty()) {
            return null;
        }
        locks.lock("email:" + email.get());
        var delivery = emails.findById(id).orElse(null);
        if (delivery == null
                || (delivery.getStatus() != EmailDeliveryStatus.PENDING
                        && delivery.getStatus() != EmailDeliveryStatus.FAILED)
                || delivery.getNextAttemptAt() == null
                || delivery.getNextAttemptAt().isAfter(clock.instant())) {
            return null;
        }
        var account = accounts.findById(id).orElseThrow();
        if (account.getStatus() != AccountStatus.PENDING
                || account.getOrganization().getStatus() != OrganizationStatus.PENDING
                || organizations.existsByDomainAndStatus(
                        account.getOrganization().getDomain(), OrganizationStatus.ACTIVE)) {
            delivery.setStatus(EmailDeliveryStatus.CANCELLED);
            delivery.setNextAttemptAt(null);
            return null;
        }
        delivery.setStatus(EmailDeliveryStatus.SENDING);
        delivery.setAttemptId(UUID.randomUUID());
        delivery.setAttempts(delivery.getAttempts() + 1);
        delivery.setLastAttemptAt(clock.instant());
        delivery.setNextAttemptAt(null);
        return new Attempt(id, delivery.getRecipient(), delivery.getAttemptId());
    }

    @Transactional
    public void accepted(Attempt attempt, String token, String messageId) {
        var delivery = current(attempt);
        if (delivery == null) {
            return;
        }
        var now = clock.instant();
        delivery.setStatus(EmailDeliveryStatus.SMTP_ACCEPTED);
        delivery.setFailureCode(null);
        delivery.setConsecutiveFailures(0);
        delivery.setSmtpMessageId(messageId);
        delivery.setTokenHash(IdentityServiceImpl.hash(token));
        delivery.setLastSentAt(now);
        delivery.setTokenExpiresAt(now.plus(Duration.ofHours(24)));
        accounts.findById(attempt.accountId()).orElseThrow().setUpdatedAt(now);
    }

    @Transactional
    public void failed(Attempt attempt, String code) {
        var delivery = current(attempt);
        if (delivery == null) {
            return;
        }
        int failures = delivery.getConsecutiveFailures() + 1;
        delivery.setConsecutiveFailures(failures);
        delivery.setStatus(EmailDeliveryStatus.FAILED);
        delivery.setFailureCode(code);
        long delaySeconds = Math.min(900, 60L << Math.min(failures - 1, 4));
        delivery.setNextAttemptAt(clock.instant().plusSeconds(delaySeconds));
        // A failed replacement must never invalidate the last accepted verification link.
    }

    private VerificationEmailEntity current(Attempt attempt) {
        locks.lock("email:" + attempt.recipient());
        return emails.findById(attempt.accountId())
                .filter(
                        e ->
                                e.getStatus() == EmailDeliveryStatus.SENDING
                                        && attempt.attemptId().equals(e.getAttemptId()))
                .orElse(null);
    }
}
