package com.betterf.identity;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.betterf.identity.api.dto.InvitationView;
import com.betterf.identity.internal.service.*;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.Multipart;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import java.time.Instant;
import java.util.*;
class SmtpInvitationMailTests {
    @Test void usesResourceTemplateEscapesNamesAndIncludesAccessibleActionAndPlainTextAlternative() throws Exception {
        var sender = mock(JavaMailSender.class);
        var message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        var mail = new SmtpInvitationMail(sender, "https://betterf.example", "no-reply@betterf.example", "smtp.example");
        var id = UUID.randomUUID(); var expiry = Instant.parse("2026-10-14T08:00:00Z");
        var claim = new InvitationTransactions.Claim(new InvitationView(id, "person@gmail.com", "SENDING", expiry, ""), "Acme <script>alert(1)</script>", "Ada & Co");
        assertThat(mail.send(claim, "x".repeat(43))).isNotBlank();
        verify(sender).send(message);
        var parts = new java.util.ArrayList<String>();
        collect(message.getContent(), parts);
        String text = parts.get(0);
        String html = parts.get(1);
        assertThat(text).contains("Accept invitation:", expiry.toString(), "https://betterf.example/invitation/accept#" + id);
        assertThat(html).contains("Accept invitation", "Ada &amp; Co", "&lt;script&gt;", expiry.toString(), "name=\"viewport\"");
        assertThat(html).doesNotContain("<script>", "{{");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("person@gmail.com");
    }
    private static void collect(Object content, java.util.List<String> parts) throws Exception {
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) collect(multipart.getBodyPart(i).getContent(), parts);
        } else if (content instanceof String text) parts.add(text);
    }
}
