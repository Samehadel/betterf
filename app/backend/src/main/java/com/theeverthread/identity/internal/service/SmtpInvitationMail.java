package com.theeverthread.identity.internal.service;

import com.theeverthread.identity.api.exception.EmailDeliveryException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.*;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class SmtpInvitationMail implements InvitationMail {
    private final JavaMailSender mail;
    private final String origin;
    private final String from;
    private final String host;
    private final String template;

    public SmtpInvitationMail(
            JavaMailSender mail,
            @Value("${theeverthread.registration.public-origin}") String origin,
            @Value("${theeverthread.registration.mail-from}") String from,
            @Value("${spring.mail.host:127.0.0.1}") String host)
            throws IOException {
        this.mail = mail;
        this.origin = origin;
        this.from = from;
        this.host = host;
        try (var stream = new ClassPathResource("mail/team-invitation.html").getInputStream()) {
            this.template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Allows template formatting whitespace while keeping all substituted values HTML escaped. */
    private static String replace(String html, String name, String value) {
        return html.replaceAll(
                "\\{\\{\\s*" + name + "\\s*}}",
                java.util.regex.Matcher.quoteReplacement(HtmlUtils.htmlEscape(value)));
    }

    public String send(InvitationTransactions.Claim claim, String token) {
        if (from.endsWith("@theeverthread.local")
                && !java.util.Set.of("127.0.0.1", "localhost", "::1", "mailpit").contains(host))
            throw new EmailDeliveryException("SMTP_FROM_NOT_CONFIGURED");
        var message = mail.createMimeMessage();
        try {
            String link = origin + "/invitation/accept#" + claim.view().id() + "." + token;
            String html = template;
            html = replace(html, "link", link);
            html = replace(html, "expiry", claim.view().expiresAt().toString());
            html = replace(html, "workspace", claim.workspaceName());
            html = replace(html, "administrator", claim.administratorName());
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(claim.view().email());
            helper.setSubject("You are invited to join your team on theeverthread");
            helper.setText(
                    claim.administratorName()
                            + " invited you to "
                            + claim.workspaceName()
                            + ". Accept invitation: "
                            + link
                            + "\nExpires: "
                            + claim.view().expiresAt(),
                    html);
            message.saveChanges();
            String id = message.getMessageID();
            mail.send(message);
            return id;
        } catch (org.springframework.mail.MailAuthenticationException exception) {
            throw new EmailDeliveryException("SMTP_AUTHENTICATION_FAILED");
        } catch (jakarta.mail.MessagingException exception) {
            throw new EmailDeliveryException("SMTP_MESSAGE_INVALID");
        } catch (org.springframework.mail.MailException exception) {
            throw new EmailDeliveryException();
        }
    }
}
