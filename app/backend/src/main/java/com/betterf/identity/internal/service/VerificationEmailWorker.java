package com.betterf.identity.internal.service;

import com.betterf.identity.api.exception.EmailDeliveryException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.sql.*;
import java.util.Base64;

import javax.sql.DataSource;

@Component
public class VerificationEmailWorker {
    private static final Logger log = LoggerFactory.getLogger(VerificationEmailWorker.class);
    private static final long LOCK_ID = 0x425446354D41494CL;
    private final DataSource dataSource;
    private final VerificationEmailTransactions transactions;
    private final VerificationMail mail;
    private final boolean enabled;
    private final SecureRandom random = new SecureRandom();

    public VerificationEmailWorker(
            DataSource dataSource,
            VerificationEmailTransactions transactions,
            VerificationMail mail,
            @Value("${betterf.registration.delivery.enabled:true}") boolean enabled) {
        this.dataSource = dataSource;
        this.transactions = transactions;
        this.mail = mail;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${betterf.registration.delivery.delay:5000}")
    public void scheduledRun() {
        if (enabled) {
            runOnce();
        }
    }

    /** The dedicated connection holds the session lock across transactions and SMTP calls. */
    public boolean runOnce() {
        try (var connection = dataSource.getConnection()) {
            if (!lockOperation(connection, "pg_try_advisory_lock")) {
                return false;
            }
            try {
                for (var id : transactions.due()) {
                    if (!connection.isValid(5)) {
                        throw new SQLException("Email worker lock connection lost");
                    }
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
            } finally {
                try {
                    if (!lockOperation(connection, "pg_advisory_unlock")) {
                        throw new SQLException("Email worker lock was lost");
                    }
                } catch (SQLException exception) {
                    connection.abort(Runnable::run);
                    throw exception;
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Could not hold the verification email worker lock", exception);
        }
    }

    private boolean lockOperation(Connection connection, String function) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT " + function + "(?)")) {
            statement.setLong(1, LOCK_ID);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }
}
