package com.betterf.identity.internal.service;

import com.betterf.identity.internal.entity.*;
import com.betterf.identity.internal.repository.*;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;

@Service
public class PendingRegistrationCleanup {
    private final AccountRepository accounts;
    private final OrganizationRepository organizations;
    private final VerificationEmailRepository emails;
    private final RegistrationLocks locks;
    private final Clock clock;

    public PendingRegistrationCleanup(
            AccountRepository accounts,
            OrganizationRepository organizations,
            VerificationEmailRepository emails,
            RegistrationLocks locks,
            Clock clock) {
        this.accounts = accounts;
        this.organizations = organizations;
        this.emails = emails;
        this.locks = locks;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${betterf.registration.cleanup-delay:3600000}")
    @Transactional
    public void deleteAbandoned() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(30));
        for (String email : accounts.abandonedEmails(cutoff, PageRequest.of(0, 100))) {
            locks.lock("email:" + email);
            accounts.findByEmail(email)
                    .filter(
                            a ->
                                    a.getStatus() == AccountStatus.PENDING
                                            && a.getOrganization().getStatus()
                                                    == OrganizationStatus.PENDING
                                            && a.getUpdatedAt().isBefore(cutoff))
                    .ifPresent(
                            account -> {
                                var organization = account.getOrganization();
                                emails.deleteById(account.getId());
                                emails.flush();
                                accounts.delete(account);
                                accounts.flush();
                                organizations.delete(organization);
                            });
        }
    }
}
