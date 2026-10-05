package com.betterf.identity;

import static org.assertj.core.api.Assertions.*;

import com.betterf.identity.api.exception.EmailDeliveryException;
import com.betterf.identity.internal.service.SmtpVerificationMail;
import com.betterf.identity.internal.service.VerificationEmailTemplate;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;

/** Exercises the real JavaMail transport against a loopback-only SMTP relay. */
class SmtpTransportIntegrationTests {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void reportsAcceptanceOnlyWhenTheRelayAcceptsMessageData(boolean accept) throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setSoTimeout(5000);
            var received = executor.submit(() -> receive(server, accept));
            var sender = new JavaMailSenderImpl();
            sender.setHost(server.getInetAddress().getHostAddress());
            sender.setPort(server.getLocalPort());
            sender.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "2000");
            sender.getJavaMailProperties().setProperty("mail.smtp.timeout", "2000");
            var mail =
                    new SmtpVerificationMail(
                            sender,
                            new VerificationEmailTemplate(),
                            "http://localhost:4200",
                            "verified@example.com",
                            sender.getHost());
            var id = UUID.randomUUID();
            String messageId = null;
            if (accept) {
                messageId = mail.send("recipient@example.com", id, "test-token");
                assertThat(messageId).isNotBlank();
            } else {
                assertThatThrownBy(() -> mail.send("recipient@example.com", id, "test-token"))
                        .isInstanceOfSatisfying(
                                EmailDeliveryException.class,
                                exception ->
                                        assertThat(exception.failureCode())
                                                .isEqualTo("SMTP_DELIVERY_FAILED"));
            }
            String payload = received.get(5, TimeUnit.SECONDS);
            assertThat(payload)
                    .contains("/verify#" + id + ".test-token", "multipart/alternative")
                    .containsPattern("Content-Type: text/plain;\\s*charset=UTF-8")
                    .containsPattern("Content-Type: text/html;\\s*charset=UTF-8")
                    .doesNotContain("${verificationUrl}");
            if (accept) {
                assertThat(payload).contains("Message-ID: " + messageId);
            }
        }
    }

    private String receive(ServerSocket server, boolean accept) throws IOException {
        try (var socket = server.accept()) {
            socket.setSoTimeout(5000);
            var reader =
                    new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var writer =
                    new PrintWriter(
                            new OutputStreamWriter(
                                    socket.getOutputStream(), StandardCharsets.UTF_8),
                            true);
            writer.print("220 local.test ESMTP\r\n");
            writer.flush();
            var payload = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals("QUIT")) {
                    writer.print("221 Bye\r\n");
                    writer.flush();
                    break;
                }
                if (line.equals("DATA")) {
                    writer.print("354 Send message\r\n");
                    writer.flush();
                    while ((line = reader.readLine()) != null && !line.equals(".")) {
                        payload.append(line).append('\n');
                    }
                    writer.print(accept ? "250 Accepted\r\n" : "554 Message rejected\r\n");
                } else {
                    writer.print("250 OK\r\n");
                }
                writer.flush();
            }
            return payload.toString();
        }
    }
}
