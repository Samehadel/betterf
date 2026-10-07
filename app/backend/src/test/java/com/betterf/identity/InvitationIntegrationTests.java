package com.betterf.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.betterf.identity.api.dto.*;
import com.betterf.identity.api.exception.*;
import com.betterf.identity.api.service.*;
import com.betterf.identity.internal.service.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Testcontainers
@SpringBootTest(properties = "betterf.registration.delivery.enabled=false")
@AutoConfigureMockMvc
class InvitationIntegrationTests {
    @Container
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:17.6-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }

    @Autowired

    IdentityService identity;
    @Autowired
    InvitationService invitations;
    @Autowired
    VerificationEmailWorker worker;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MockMvc mvc;
    @MockitoBean
    VerificationMail verification;
    @MockitoBean
    InvitationMail mail;
    @MockitoBean
    Clock clock;
    final Instant now = Instant.parse("2026-10-07T08:00:00Z");

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE TEAM_INVITATION, VERIFICATION_EMAIL, ACCOUNT, ORGANIZATION CASCADE");
        when(clock.instant()).thenReturn(now);
        when(mail.send(any(), anyString())).thenReturn("<invitation@test.local>");
        admin("admin@example.com", "acme.com");
    }

    void admin(String email, String domain) {
        identity.register(
                new RegistrationRequest(
                        "Acme",
                        "https://" + domain,
                        "Engineering",
                        "Ada",
                        email,
                        "Validpass!",
                        "OTHER"));
        var link = new String[1];
        var id = new UUID[1];
        doAnswer(
                        call -> {
                            id[0] = call.getArgument(1);
                            link[0] = call.getArgument(2);
                            return "<verification@test.local>";
                        })
                .when(verification)
                .send(eq(email), any(), anyString());
        worker.runOnce();
        identity.verify(id[0], link[0]);
    }

    InvitationView send(String email) {
        return invitations.send("admin@example.com", new InvitationRequest(email));
    }

    void rejected(String email, String code) {
        assertThatThrownBy(() -> send(email))
                .isInstanceOfSatisfying(
                        IdentityException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void sendsPersonalAddressWithoutCreatingMembershipAndStoresOnlyHashWithSevenDayExpiry() {
        var result = send("Person@gmail.com");
        assertThat(result.email()).isEqualTo("person@gmail.com");
        assertThat(result.status()).isEqualTo("SMTP_ACCEPTED");
        assertThat(result.expiresAt()).isEqualTo(now.plus(Duration.ofDays(7)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACCOUNT", Integer.class)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT LENGTH(TOKEN_HASH) FROM TEAM_INVITATION", Integer.class))
                .isEqualTo(64);
        assertThat(jdbc.queryForObject("SELECT SMTP_MESSAGE_ID FROM TEAM_INVITATION", String.class))
                .isEqualTo("<invitation@test.local>");
        rejected("PERSON@gmail.com", "INVITATION_PENDING");
        verify(mail, times(1)).send(any(), anyString());
    }

    @Test
    void membershipWinsOverPendingAndSelfAndOtherCompanyAreRejected() {
        rejected("admin@example.com", "ALREADY_MEMBER");
        admin("other@example.com", "other.com");
        rejected("other@example.com", "OTHER_COMPANY_MEMBER");
        send("new@example.com");
        // A concurrently accepted invitation must take precedence over the old pending one.
        jdbc.update("UPDATE ACCOUNT SET EMAIL='new@example.com' WHERE EMAIL='other@example.com'");
        rejected("new@example.com", "OTHER_COMPANY_MEMBER");
        verify(mail, times(1)).send(any(), anyString());
    }

    @Test
    void pendingOtherCompanyDoesNotReserveMembershipAndExpiredOrRevokedCanBeReplaced() {
        send("new@example.com");
        admin("other@example.com", "other.com");
        assertThat(
                        invitations
                                .send("other@example.com", new InvitationRequest("new@example.com"))
                                .status())
                .isEqualTo("SMTP_ACCEPTED");
        when(clock.instant()).thenReturn(now.plus(Duration.ofDays(7)));
        send("new@example.com");
        jdbc.update(
                "UPDATE TEAM_INVITATION SET STATUS='REVOKED' WHERE ORGANIZATION_ID=(SELECT"
                        + " ORGANIZATION_ID FROM ACCOUNT WHERE EMAIL='admin@example.com')");
        send("new@example.com");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TEAM_INVITATION", Integer.class))
                .isEqualTo(2);
        verify(mail, times(4)).send(any(), anyString());
    }

    @Test
    void configurableCapCountsAdminButNotInvitationsAndAlsoBlocksFreshExpiredInvitations() {
        jdbc.update("UPDATE ORGANIZATION SET MAX_ACTIVE_ACCOUNTS=2");
        for (int i = 0; i < 4; i++) send("person" + i + "@gmail.com");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACCOUNT", Integer.class)).isEqualTo(1);
        jdbc.update("UPDATE ORGANIZATION SET MAX_ACTIVE_ACCOUNTS=1");
        assertThatThrownBy(() -> send("fresh@example.com"))
                .hasMessage(
                        "Your organization has reached its limit of 1 active accounts. You cannot"
                                + " send invitations.");
        when(clock.instant()).thenReturn(now.plus(Duration.ofDays(8)));
        rejected("person0@gmail.com", "ACCOUNT_LIMIT");
        verify(mail, times(4)).send(any(), anyString());
    }

    @Test
    void defaultCapIncludesAdministrator() {
        var org = identity.current("admin@example.com").organizationId();
        for (int i = 0; i < 9; i++)
            jdbc.update(
                    "INSERT INTO ACCOUNT(ID, EMAIL, FULL_NAME, PASSWORD_HASH, PROFESSIONAL_ROLE,"
                            + " STATUS, ORGANIZATION_ID, ACCESS_ROLE, UPDATED_AT) VALUES (?, ?,"
                            + " 'Member', 'unused', 'OTHER', 'ACTIVE', ?, 'MEMBER', ?)",
                    UUID.randomUUID(),
                    "member" + i + "@example.com",
                    org,
                    java.sql.Timestamp.from(now));
        assertThatThrownBy(() -> send("fresh@example.com"))
                .hasMessageContaining("limit of 10 active accounts");
        verifyNoInteractions(mail);
    }

    @Test
    void failedSendInvalidatesTokenAndAllowsCorrectedAddressOrRetry() {
        when(mail.send(any(), anyString())).thenThrow(new EmailDeliveryException());
        assertThat(send("new@example.com").status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT TOKEN_HASH FROM TEAM_INVITATION", String.class))
                .isNull();
        doReturn("<retry@test.local>").when(mail).send(any(), anyString());
        assertThat(send("new@example.com").status()).isEqualTo("SMTP_ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TEAM_INVITATION", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void simultaneousRequestsCommitOneAttemptAndNeverSendDuplicateEmail() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            entered.countDown();
                            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                            return "<accepted@test.local>";
                        })
                .when(mail)
                .send(any(), anyString());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> send("new@example.com"));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(send("NEW@example.com").status()).isEqualTo("SENDING");
                assertThat(
                                invitations
                                        .status(
                                                "admin@example.com",
                                                new InvitationRequest("new@example.com"))
                                        .status())
                        .isEqualTo("SENDING");
            } finally {
                release.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo("SMTP_ACCEPTED");
        }
        verify(mail, times(1)).send(any(), anyString());
    }

    @Test
    void completionPersistenceFailureLeavesAmbiguousAttemptAndRepeatedRequestsCannotResend() {
        jdbc.execute(
                "CREATE FUNCTION reject_invitation_completion() RETURNS TRIGGER AS $$ BEGIN IF"
                    + " NEW.STATUS='SMTP_ACCEPTED' THEN RAISE EXCEPTION 'Simulated failure'; END"
                    + " IF; RETURN NEW; END; $$ LANGUAGE plpgsql");
        jdbc.execute(
                "CREATE TRIGGER reject_invitation_completion BEFORE UPDATE ON TEAM_INVITATION FOR"
                        + " EACH ROW EXECUTE FUNCTION reject_invitation_completion()");
        try {
            assertThatThrownBy(() -> send("new@example.com")).isInstanceOf(RuntimeException.class);
            assertThat(send("new@example.com").status()).isEqualTo("SENDING");
            verify(mail, times(1)).send(any(), anyString());
        } finally {
            jdbc.execute("DROP TRIGGER reject_invitation_completion ON TEAM_INVITATION");
            jdbc.execute("DROP FUNCTION reject_invitation_completion()");
        }
    }

    @Test
    void serviceAndHttpRecheckRoleVerificationCompanyOwnershipCsrfAndInput() throws Exception {
        mvc.perform(
                        post("/api/invitations")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"email\":\"new@gmail.com\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/invitations")
                                .with(user("admin@example.com").roles("ADMIN"))
                                .contentType("application/json")
                                .content("{\"email\":\"new@gmail.com\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/invitations")
                                .with(user("admin@example.com").roles("ADMIN"))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"email\":\"invalid\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/invitations")
                                .with(user("admin@example.com").roles("ADMIN"))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"email\":\"new@gmail.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SMTP_ACCEPTED"));
        admin("other@example.com", "other.com");
        mvc.perform(
                        post("/api/invitations/status")
                                .with(user("other@example.com").roles("ADMIN"))
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"email\":\"new@gmail.com\"}"))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE ACCOUNT SET ACCESS_ROLE='MEMBER' WHERE EMAIL='admin@example.com'");
        assertThatThrownBy(() -> send("fresh@example.com"))
                .isInstanceOfSatisfying(
                        IdentityException.class, e -> assertThat(e.status()).isEqualTo(403));
        jdbc.update(
                "UPDATE ACCOUNT SET ACCESS_ROLE='ADMIN', STATUS='PENDING' WHERE"
                        + " EMAIL='admin@example.com'");
        assertThatThrownBy(() -> send("fresh@example.com"))
                .hasMessageContaining("verified administrator");
    }

    @Test
    void historySurvivesNavigationAndIsScopedToTheVerifiedAdministratorsCompany() throws Exception {
        assertThat(invitations.history("admin@example.com", 0).invitations()).isEmpty();
        var saved = send("saved@gmail.com");
        admin("other@example.com", "other.com");
        invitations.send("other@example.com", new InvitationRequest("other-recipient@gmail.com"));
        assertThat(invitations.history("admin@example.com", 0).invitations())
                .extracting(InvitationView::id)
                .containsExactly(saved.id());
        mvc.perform(get("/api/invitations").with(user("admin@example.com").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invitations[0].email").value("saved@gmail.com"))
                .andExpect(jsonPath("$.data.invitations[0].status").value("SMTP_ACCEPTED"))
                .andExpect(jsonPath("$.data.invitations[0].tokenHash").doesNotExist())
                .andExpect(jsonPath("$.data.hasMore").value(false));
    }

    @Test
    void historyIsBoundedAndPaginatesOlderInvitations() {
        for (int i = 0; i < 26; i++) {
            when(clock.instant()).thenReturn(now.plusSeconds(i));
            send("recipient" + i + "@gmail.com");
        }
        var first = invitations.history("admin@example.com", 0);
        var second = invitations.history("admin@example.com", 1);
        assertThat(first.invitations()).hasSize(25);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.invitations()).hasSize(1);
        assertThat(second.hasMore()).isFalse();
        assertThat(first.invitations().getFirst().email()).isEqualTo("recipient25@gmail.com");
        assertThat(second.invitations().getFirst().email()).isEqualTo("recipient0@gmail.com");
    }

    @Test
    void historyRejectsAnonymousUnverifiedAndNonAdministratorAccessAndInvalidPages()
            throws Exception {
        mvc.perform(get("/api/invitations")).andExpect(status().isForbidden());
        mvc.perform(get("/api/invitations").with(user("admin@example.com").roles("MEMBER")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/invitations")
                                .param("page", "-1")
                                .with(user("admin@example.com").roles("ADMIN")))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE ACCOUNT SET STATUS='PENDING' WHERE EMAIL='admin@example.com'");
        assertThatThrownBy(() -> invitations.history("admin@example.com", 0))
                .isInstanceOfSatisfying(
                        IdentityException.class,
                        error -> assertThat(error.status()).isEqualTo(403));
    }
}
