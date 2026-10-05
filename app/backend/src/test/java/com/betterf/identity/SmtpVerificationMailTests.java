package com.betterf.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.betterf.identity.api.exception.EmailDeliveryException;
import com.betterf.identity.internal.service.SmtpVerificationMail;
import com.betterf.identity.internal.service.VerificationEmailTemplate;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.exceptions.TemplateInputException;

import java.util.Properties;
import java.util.UUID;

class SmtpVerificationMailTests {
    @Test
    void sendsHtmlAndPlainTextWithVerificationCredentialOnlyInLinkFragment() throws Exception {
        var sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        var mail =
                new SmtpVerificationMail(
                        sender,
                        new VerificationEmailTemplate(),
                        "https://betterf.example/",
                        "no-reply@betterf.example",
                        "127.0.0.1");
        var id = UUID.randomUUID();
        mail.send("ada@example.com", id, "test-token");
        var message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(message.capture());
        assertThat(message.getValue().getAllRecipients()[0].toString())
                .isEqualTo("ada@example.com");
        String link = "https://betterf.example/verify#" + id + ".test-token";
        assertThat(message.getValue().getSubject()).isEqualTo("Verify your BetterF email");
        assertThat(message.getValue().isMimeType("multipart/*")).isTrue();
        assertThat(body(message.getValue(), "text/plain"))
                .contains(link, "24 hours", "If you did not register")
                .doesNotContain("${verificationUrl}", "<html");
        assertThat(body(message.getValue(), "text/html"))
                .contains("href=\"" + link + "\"", "Verify my email", "24 hours", "#6850c3")
                .doesNotContain("${verificationUrl}", "th:href", "th:text", "/verify?");
    }

    @Test
    void deliveryFailureIsSafeAndTypedForPersistingFailureState() throws Exception {
        var sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("private SMTP diagnostic"))
                .when(sender)
                .send(any(MimeMessage.class));
        var mail =
                new SmtpVerificationMail(
                        sender,
                        new VerificationEmailTemplate(),
                        "https://betterf.example",
                        "no-reply@betterf.example",
                        "127.0.0.1");
        assertThatThrownBy(() -> mail.send("ada@example.com", UUID.randomUUID(), "test-token"))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageNotContaining("private SMTP diagnostic");
    }

    @Test
    void brevoRequiresAConfiguredSenderInsteadOfTheLocalDefault() throws Exception {
        var sender = mock(JavaMailSender.class);
        var mail =
                new SmtpVerificationMail(
                        sender,
                        new VerificationEmailTemplate(),
                        "http://localhost:4200",
                        "no-reply@betterf.local",
                        "smtp-relay.brevo.com");
        assertThatThrownBy(() -> mail.send("ada@example.com", UUID.randomUUID(), "token"))
                .isInstanceOfSatisfying(
                        EmailDeliveryException.class,
                        exception ->
                                assertThat(exception.failureCode())
                                        .isEqualTo("SMTP_FROM_NOT_CONFIGURED"));
        verifyNoInteractions(sender);
    }

    @Test
    void escapesHtmlWithoutChangingPlainTextOrLeakingPreviousLinks() throws Exception {
        var template = new VerificationEmailTemplate();
        String firstLink = "https://betterf.example/verify#first&\"<tag>";
        var first = template.render(firstLink);
        assertThat(first.plainText()).contains(firstLink);
        assertThat(first.html())
                .contains("https://betterf.example/verify#first&amp;&quot;&lt;tag&gt;")
                .doesNotContain(firstLink, "${verificationUrl}");
        var next = template.render("https://betterf.example/verify#second");
        assertThat(next.html())
                .contains("/verify#second")
                .doesNotContain("first", "${verificationUrl}");
        assertThat(next.plainText())
                .contains("/verify#second")
                .doesNotContain("first", "${verificationUrl}");
    }

    @Test
    void templateFailureIsRecordedAsMessageFailureWithoutSending() {
        var sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        var template = mock(VerificationEmailTemplate.class);
        when(template.render(anyString()))
                .thenThrow(new TemplateInputException("private template diagnostic"));
        var mail =
                new SmtpVerificationMail(
                        sender,
                        template,
                        "https://betterf.example",
                        "no-reply@betterf.example",
                        "localhost");
        assertThatThrownBy(() -> mail.send("ada@example.com", UUID.randomUUID(), "test-token"))
                .isInstanceOfSatisfying(
                        EmailDeliveryException.class,
                        exception ->
                                assertThat(exception.failureCode())
                                        .isEqualTo("SMTP_MESSAGE_INVALID"))
                .hasMessageNotContaining("private template diagnostic");
        verify(sender, never()).send(any(MimeMessage.class));
    }

    private String body(Part part, String mimeType) throws Exception {
        if (part.isMimeType(mimeType)) {
            assertThat(part.getContentType()).containsIgnoringCase("charset=UTF-8");
            return (String) part.getContent();
        }
        if (part.getContent() instanceof Multipart multipart) {
            for (int index = 0; index < multipart.getCount(); index++) {
                String content = body(multipart.getBodyPart(index), mimeType);
                if (content != null) return content;
            }
        }
        return null;
    }
}
