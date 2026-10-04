package com.betterf.identity.internal.service;

import com.betterf.identity.api.exception.EmailDeliveryException;

import jakarta.mail.MessagingException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SmtpVerificationMail implements VerificationMail {
    private final JavaMailSender mail;
    private final String origin;
    private final String from;
    private final String host;

    public SmtpVerificationMail(
            JavaMailSender mail,
            @Value("${betterf.registration.public-origin}") String origin,
            @Value("${betterf.registration.mail-from}") String from,
            @Value("${spring.mail.host:127.0.0.1}") String host) {
        this.mail = mail;
        this.origin = origin;
        this.from = from;
        this.host = host;
    }

    public String send(String email, UUID id, String token) {
        if (from.endsWith("@betterf.local")
                && !java.util.Set.of("127.0.0.1", "localhost", "::1", "mailpit").contains(host)) {
            throw new EmailDeliveryException("SMTP_FROM_NOT_CONFIGURED");
        }
        var message = mail.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject("Verify your BetterF email");
            // The fragment keeps the credential out of HTTP access logs and Referer headers.
            helper.setText(
                    "Complete your BetterF organization registration:\n"
                            + origin
                            + "/verify#"
                            + id
                            + "."
                            + token
                            + "\n\n"
                            + "This link expires in 24 hours. If you did not register, ignore this"
                            + " email.");
            message.saveChanges();
            String messageId = message.getMessageID();
            mail.send(message);
            return messageId;
        } catch (MailAuthenticationException exception) {
            throw new EmailDeliveryException("SMTP_AUTHENTICATION_FAILED");
        } catch (MessagingException exception) {
            throw new EmailDeliveryException("SMTP_MESSAGE_INVALID");
        } catch (org.springframework.mail.MailException exception) {
            throw new EmailDeliveryException();
        }
    }
}
