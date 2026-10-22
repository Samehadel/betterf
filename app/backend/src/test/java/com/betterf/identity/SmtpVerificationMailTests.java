package com.betterf.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.betterf.identity.api.exception.EmailDeliveryException;
import com.betterf.identity.internal.service.SmtpVerificationMail;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;
import java.util.UUID;

class SmtpVerificationMailTests {
    @Test
    void sendsVerificationCredentialOnlyInLinkFragment() throws Exception {
        var sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        var mail =
                new SmtpVerificationMail(
                        sender, "https://betterf.example", "no-reply@betterf.example", "127.0.0.1");
        var id = UUID.randomUUID();
        mail.send("ada@example.com", id, "test-token");
        var message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(message.capture());
        assertThat(message.getValue().getAllRecipients()[0].toString())
                .isEqualTo("ada@example.com");
        assertThat(message.getValue().getContent().toString())
                .contains("https://betterf.example/verify#" + id + ".test-token")
                .contains("24 hours");
    }

    @Test
    void deliveryFailureIsSafeAndTypedForPersistingFailureState() {
        var sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("private SMTP diagnostic"))
                .when(sender)
                .send(any(MimeMessage.class));
        var mail =
                new SmtpVerificationMail(
                        sender, "https://betterf.example", "no-reply@betterf.example", "127.0.0.1");
        assertThatThrownBy(() -> mail.send("ada@example.com", UUID.randomUUID(), "test-token"))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageNotContaining("private SMTP diagnostic");
    }

    @Test
    void brevoRequiresAConfiguredSenderInsteadOfTheLocalDefault() {
        var sender = mock(JavaMailSender.class);
        var mail =
                new SmtpVerificationMail(
                        sender,
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
}
