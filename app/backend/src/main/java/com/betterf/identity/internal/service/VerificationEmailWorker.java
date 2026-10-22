package com.betterf.identity.internal.service;

import com.betterf.identity.api.exception.EmailDeliveryException;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public class VerificationEmailWorker {
    private static final Logger log = LoggerFactory.getLogger(VerificationEmailWorker.class);
    private final VerificationEmailTransactions transactions;
    private final VerificationMail mail;
    private final SecureRandom random = new SecureRandom();

    public VerificationEmailWorker(
            VerificationEmailTransactions transactions, VerificationMail mail) {
        this.transactions = transactions;
        this.mail = mail;
    }

    /** A skipped invocation returns null when another instance owns the lease. */
    @SchedulerLock(
            name = "verification-email-worker",
            lockAtMostFor = "${betterf.registration.delivery.lock-at-most-for:10m}")
    public Boolean runOnce() {
        for (var id : transactions.due()) {
            var attempt = transactions.claim(id);
            if (attempt == null) {
                continue;
            }
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            String messageId;
            try {
                messageId = mail.send(attempt.recipient(), attempt.accountId(), token);
            } catch (EmailDeliveryException exception) {
                transactions.failed(attempt, exception.failureCode());
                log.warn(
                        "Verification email attempt {} failed: {}",
                        attempt.attemptId(),
                        exception.failureCode());
                continue;
            }
            // Persistence errors after acceptance leave SENDING for investigation; never
            // automatically repeat an SMTP submission with an uncertain outcome.
            transactions.accepted(attempt, token, messageId);
        }
        return true;
    }
}
