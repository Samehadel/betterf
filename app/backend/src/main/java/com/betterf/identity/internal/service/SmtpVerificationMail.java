package com.betterf.identity.internal.service;

import com.betterf.identity.api.exception.EmailDeliveryException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SmtpVerificationMail implements VerificationMail {
    private final JavaMailSender mail;
    private final String origin;
    private final String from;

    public SmtpVerificationMail(
            JavaMailSender mail,
            @Value("${betterf.registration.public-origin}") String origin,
            @Value("${betterf.registration.mail-from}") String from) {
        this.mail = mail;
        this.origin = origin;
        this.from = from;
    }

    public void send(String email, UUID id, String token) {
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Verify your BetterF email");
        // The fragment keeps the credential out of HTTP access logs and Referer headers.
        message.setText(
                "Complete your BetterF organization registration:\n"
                        + origin
                        + "/verify#"
                        + id
                        + "."
                        + token
                        + "\n\n"
                        + "This link expires in 24 hours. If you did not register, ignore this"
                        + " email.");
        try {
            mail.send(message);
        } catch (org.springframework.mail.MailException exception) {
            throw new EmailDeliveryException();
        }
    }
}
