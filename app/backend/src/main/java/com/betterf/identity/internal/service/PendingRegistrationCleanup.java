package com.betterf.identity.internal.service;

import com.betterf.identity.internal.entity.*;
import com.betterf.identity.internal.repository.*;

import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;

@Service
public class PendingRegistrationCleanup {
    private final AccountRepository accounts;
    private final VerificationEmailRepository emails;
    private final RegistrationLocks locks;
    private final Clock clock;
    private final JdbcTemplate jdbc;

    public PendingRegistrationCleanup(
            AccountRepository accounts,
            VerificationEmailRepository emails,
            RegistrationLocks locks,
            Clock clock,
            JdbcTemplate jdbc) {
        this.accounts = accounts;
        this.emails = emails;
        this.locks = locks;
        this.clock = clock;
        this.jdbc = jdbc;
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
                                if (emails.findById(account.getId())
                                        .filter(e -> e.getStatus() == EmailDeliveryStatus.SENDING)
                                        .isPresent()) {
                                    return;
                                }
                                // Explicit delete order satisfies foreign keys without flushing the
                                // entire persistence context between individual entity removals.
                                jdbc.update(
                                        "DELETE FROM VERIFICATION_EMAIL WHERE ACCOUNT_ID = ?",
                                        account.getId());
                                jdbc.update("DELETE FROM ACCOUNT WHERE ID = ?", account.getId());
                                jdbc.update(
                                        "DELETE FROM ORGANIZATION WHERE ID = ?",
                                        organization.getId());
                            });
        }
    }
}
