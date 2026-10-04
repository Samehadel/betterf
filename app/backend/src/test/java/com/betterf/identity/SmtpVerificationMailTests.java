package com.betterf.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.betterf.identity.api.exception.EmailDeliveryException;
import com.betterf.identity.internal.service.SmtpVerificationMail;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.UUID;

class SmtpVerificationMailTests {
    @Test
    void sendsVerificationCredentialOnlyInLinkFragment() {
        var sender = mock(JavaMailSender.class);
        var mail =
                new SmtpVerificationMail(
                        sender, "https://betterf.example", "no-reply@betterf.example");
        var id = UUID.randomUUID();
        mail.send("ada@example.com", id, "test-token");
        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        assertThat(message.getValue().getTo()).containsExactly("ada@example.com");
        assertThat(message.getValue().getText())
                .contains("https://betterf.example/verify#" + id + ".test-token")
                .contains("24 hours");
    }

    @Test
    void deliveryFailureIsSafeAndTypedForPersistingFailureState() {
        var sender = mock(JavaMailSender.class);
        doThrow(new MailSendException("private SMTP diagnostic"))
                .when(sender)
                .send(any(SimpleMailMessage.class));
        var mail =
                new SmtpVerificationMail(
                        sender, "https://betterf.example", "no-reply@betterf.example");
        assertThatThrownBy(() -> mail.send("ada@example.com", UUID.randomUUID(), "test-token"))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageNotContaining("private SMTP diagnostic");
    }
}
