package com.betterf.identity.internal.service;

import com.betterf.identity.api.exception.EmailDeliveryException;

import jakarta.mail.MessagingException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.thymeleaf.exceptions.TemplateEngineException;

import java.util.UUID;

@Component
public class SmtpVerificationMail implements VerificationMail {
    private final JavaMailSender mail;
    private final VerificationEmailTemplate template;
    private final String origin;
    private final String from;
    private final String host;

    public SmtpVerificationMail(
            JavaMailSender mail,
            VerificationEmailTemplate template,
            @Value("${betterf.registration.public-origin}") String origin,
            @Value("${betterf.registration.mail-from}") String from,
            @Value("${spring.mail.host:127.0.0.1}") String host) {
        this.mail = mail;
        this.template = template;
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
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject("Verify your BetterF email");
            // The fragment keeps the credential out of HTTP access logs and Referer headers.
            String verificationUrl = origin.replaceAll("/+$", "") + "/verify#" + id + "." + token;
            var content = template.render(verificationUrl);
            helper.setText(content.plainText(), content.html());
            message.saveChanges();
            String messageId = message.getMessageID();
            mail.send(message);
            return messageId;
        } catch (MailAuthenticationException exception) {
            throw new EmailDeliveryException("SMTP_AUTHENTICATION_FAILED");
        } catch (MessagingException | TemplateEngineException exception) {
            throw new EmailDeliveryException("SMTP_MESSAGE_INVALID");
        } catch (org.springframework.mail.MailException exception) {
            throw new EmailDeliveryException();
        }
    }
}
