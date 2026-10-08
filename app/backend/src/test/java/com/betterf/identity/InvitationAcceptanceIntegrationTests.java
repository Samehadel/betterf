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
class InvitationAcceptanceIntegrationTests {
    @Container
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:17.6-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }

    @Autowired IdentityService identity;
    @Autowired InvitationService invitations;
    @Autowired InvitationAcceptanceService acceptance;
    @Autowired VerificationEmailTransactions delivery;
    @Autowired VerificationEmailWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.betterf.foundation.internal.security.SessionLogin sessionLogin;

    @MockitoBean VerificationMail verification;
    @MockitoBean InvitationMail mail;
    @MockitoBean Clock clock;
    final Instant now = Instant.parse("2026-10-07T08:00:00Z");

    @BeforeEach
    void setup() {
        jdbc.execute(
                "TRUNCATE REFRESH_SESSION, TEAM_INVITATION, VERIFICATION_EMAIL, ACCOUNT,"
                    + " ORGANIZATION CASCADE");
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

    InvitationLinkRequest invite(String actor, String email) {
        var token = new String[1];
        doAnswer(
                        call -> {
                            token[0] = call.getArgument(1);
                            return "<invitation@test.local>";
                        })
                .when(mail)
                .send(any(), anyString());
        var invitation = invitations.send(actor, new InvitationRequest(email));
        return new InvitationLinkRequest(invitation.id(), token[0]);
    }

    MemberRegistrationRequest registration(InvitationLinkRequest link) {
        return new MemberRegistrationRequest(link, "Alice Recipient", "OTHER", "Memberpass!");
    }

    void rejected(InvitationLinkRequest link, String code) {
        assertThatThrownBy(() -> acceptance.accept(registration(link)))
                .isInstanceOfSatisfying(
                        IdentityException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void previewDoesNotRegisterAndAcceptancePreservesHistoryWithoutReplayOrReactivation() {
        var link = invite("admin@example.com", "alice@example.com");
        assertThat(acceptance.preview(link).status()).isEqualTo("READY");
        assertThat(acceptance.preview(link).email()).isEqualTo("alice@example.com");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACCOUNT", Integer.class)).isEqualTo(1);
        var account = acceptance.accept(registration(link));
        assertThat(account.accessRole()).isEqualTo("MEMBER");
        assertThat(account.organizationId())
                .isEqualTo(identity.current("admin@example.com").organizationId());
        assertThat(jdbc.queryForObject("SELECT TOKEN_HASH FROM TEAM_INVITATION", String.class))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT ACCEPTED_AT IS NOT NULL FROM TEAM_INVITATION",
                                Boolean.class))
                .isTrue();
        jdbc.update("UPDATE ACCOUNT SET STATUS='PENDING' WHERE EMAIL='alice@example.com'");
        when(clock.instant()).thenReturn(now.plus(Duration.ofDays(8)));
        assertThat(acceptance.preview(link).status()).isEqualTo("INVITATION_USED");
        rejected(link, "INVITATION_USED");
        assertThat(identity.credentials("alice@example.com").active()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM VERIFICATION_EMAIL", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void validationAndCapacityFailuresPreserveInvitationAndCanBeRetried() {
        var link = invite("admin@example.com", "alice@example.com");
        assertThatThrownBy(
                        () ->
                                acceptance.accept(
                                        new MemberRegistrationRequest(
                                                link, " ", "OTHER", "Memberpass!")))
                .isInstanceOf(IdentityException.class);
        assertThatThrownBy(
                        () ->
                                acceptance.accept(
                                        new MemberRegistrationRequest(
                                                link, "Alice", "OTHER", "weak")))
                .isInstanceOf(IdentityException.class);
        assertThatThrownBy(
                        () ->
                                acceptance.accept(
                                        new MemberRegistrationRequest(
                                                link, "Alice", "ADMIN", "Memberpass!")))
                .isInstanceOf(IdentityException.class);
        jdbc.update("UPDATE ORGANIZATION SET MAX_ACTIVE_ACCOUNTS=1");
        assertThat(acceptance.preview(link).message())
                .isEqualTo(
                        "This organization has reached its limit of 1 active accounts. Your"
                                + " invitation cannot be accepted. Please contact the organization"
                                + " administrator.");
        rejected(link, "ACCOUNT_LIMIT");
        assertThat(jdbc.queryForObject("SELECT STATUS FROM TEAM_INVITATION", String.class))
                .isEqualTo("SMTP_ACCEPTED");
        jdbc.update("UPDATE ORGANIZATION SET MAX_ACTIVE_ACCOUNTS=2");
        assertThat(acceptance.accept(registration(link)).accessRole()).isEqualTo("MEMBER");
    }

    @Test
    void expiredRevokedReplacedAndUnrecognizedLinksCannotActivate() {
        var link = invite("admin@example.com", "alice@example.com");
        when(clock.instant()).thenReturn(now.plus(Duration.ofDays(7)));
        rejected(link, "INVITATION_UNUSABLE");
        when(clock.instant()).thenReturn(now);
        jdbc.update("UPDATE TEAM_INVITATION SET STATUS='REVOKED'");
        rejected(link, "INVITATION_UNUSABLE");
        var replacement = invite("admin@example.com", "alice@example.com");
        rejected(link, "INVALID_INVITATION");
        rejected(new InvitationLinkRequest(UUID.randomUUID(), link.token()), "INVALID_INVITATION");
        assertThat(acceptance.preview(replacement).status()).isEqualTo("READY");
    }

    @Test
    void acceptanceCancelsUnfinishedSignupAndCannotRestoreItsVerificationEvenWithSmtpInFlight() {
        identity.register(
                new RegistrationRequest(
                        "Abandoned",
                        "https://abandoned.com",
                        "Engineering",
                        "Old profile",
                        "alice@example.com",
                        "Oldpassword!",
                        "OTHER"));
        var accountId =
                jdbc.queryForObject(
                        "SELECT ID FROM ACCOUNT WHERE EMAIL='alice@example.com'", UUID.class);
        var attempt = delivery.claim(accountId);
        var link = invite("admin@example.com", "alice@example.com");
        assertThat(acceptance.preview(link).status()).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ORGANIZATION", Integer.class))
                .isEqualTo(2);
        var member = acceptance.accept(registration(link));
        assertThat(member.id()).isEqualTo(accountId);
        assertThat(member.fullName()).isEqualTo("Alice Recipient");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ORGANIZATION", Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM VERIFICATION_EMAIL WHERE ACCOUNT_ID=?",
                                Integer.class,
                                accountId))
                .isZero();
        delivery.accepted(attempt, "a".repeat(43), "<late@test.local>");
        assertThatThrownBy(() -> identity.verify(accountId, "a".repeat(43)))
                .isInstanceOf(IdentityException.class);
        assertThat(identity.current("alice@example.com").accessRole()).isEqualTo("MEMBER");
    }

    @Test
    void existingMembershipInSameOrOtherOrganizationIsNeverOverwritten() {
        var first = invite("admin@example.com", "alice@example.com");
        admin("other@example.com", "other.com");
        var other = invite("other@example.com", "alice@example.com");
        acceptance.accept(registration(first));
        rejected(other, "OTHER_COMPANY_MEMBER");
        // Represent another completed flow that established this membership without using its link.
        jdbc.update(
                "UPDATE TEAM_INVITATION SET TOKEN_HASH=ACCEPTED_TOKEN_HASH,"
                        + " ACCEPTED_TOKEN_HASH=NULL, STATUS='SMTP_ACCEPTED'");
        rejected(first, "ALREADY_MEMBER");
    }

    @Test
    void simultaneousAcceptancesCannotExceedCapacityOrCreateTwoMemberships() throws Exception {
        var first = invite("admin@example.com", "alice@example.com");
        var second = invite("admin@example.com", "bob@example.com");
        jdbc.update("UPDATE ORGANIZATION SET MAX_ACTIVE_ACCOUNTS=2");
        var outcomes = compete(first, second);
        assertThat(outcomes).containsExactlyInAnyOrder("ACCEPTED", "ACCOUNT_LIMIT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ACCOUNT WHERE STATUS='ACTIVE'",
                                Integer.class))
                .isEqualTo(2);
    }

    @Test
    void competingCompaniesAndDuplicateSubmissionsHaveExactlyOneWinner() throws Exception {
        var first = invite("admin@example.com", "alice@example.com");
        admin("other@example.com", "other.com");
        var second = invite("other@example.com", "alice@example.com");
        assertThat(compete(first, second))
                .containsExactlyInAnyOrder("ACCEPTED", "OTHER_COMPANY_MEMBER");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ACCOUNT WHERE EMAIL='alice@example.com'",
                                Integer.class))
                .isEqualTo(1);
        var duplicate = invite("admin@example.com", "duplicate@example.com");
        assertThat(compete(duplicate, duplicate))
                .containsExactlyInAnyOrder("ACCEPTED", "INVITATION_USED");
    }

    List<String> compete(InvitationLinkRequest first, InvitationLinkRequest second)
            throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures =
                    List.of(first, second).stream()
                            .map(
                                    link ->
                                            pool.submit(
                                                    () -> {
                                                        start.await();
                                                        try {
                                                            acceptance.accept(registration(link));
                                                            return "ACCEPTED";
                                                        } catch (IdentityException e) {
                                                            return e.code();
                                                        }
                                                    }))
                            .toList();
            start.countDown();
            return List.of(
                    futures.get(0).get(15, TimeUnit.SECONDS),
                    futures.get(1).get(15, TimeUnit.SECONDS));
        }
    }

    String body(InvitationLinkRequest link) {
        return "{\"invitation\":{\"id\":\""
                + link.id()
                + "\",\"token\":\""
                + link.token()
                + "\"},\"fullName\":\"Alice"
                + " Recipient\",\"professionalRole\":\"OTHER\",\"password\":\"Memberpass!\"}";
    }

    @Test
    void httpEnforcesCsrfAutomaticallyLogsInAndUsedLinkDoesNotCreateSession() throws Exception {
        var link = invite("admin@example.com", "alice@example.com");
        mvc.perform(
                        post("/api/auth/invitation/accept")
                                .contentType("application/json")
                                .content(body(link)))
                .andExpect(status().isForbidden());
        var result =
                mvc.perform(
                                post("/api/auth/invitation/accept")
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content(body(link)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.accessRole").value("MEMBER"))
                        .andReturn();
        var session =
                (org.springframework.mock.web.MockHttpSession)
                        result.getRequest().getSession(false);
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("alice@example.com"));
        mvc.perform(get("/api/invitations").session(session)).andExpect(status().isForbidden());
        var replay =
                mvc.perform(
                                post("/api/auth/invitation/accept")
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content(body(link)))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.error.code").value("INVITATION_USED"))
                        .andReturn();
        assertThat(replay.getRequest().getSession(false)).isNull();
    }

    @Test
    void previewInvalidatesDifferentBrowserSessionAndRefreshCredentialWithoutChangingItsAccount()
            throws Exception {
        var login =
                mvc.perform(
                                post("/api/auth/login")
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content(
                                                "{\"email\":\"admin@example.com\",\"password\":\"Validpass!\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        var session =
                (org.springframework.mock.web.MockHttpSession) login.getRequest().getSession(false);
        var cookie = login.getResponse().getCookie("BETTERF_REFRESH");
        var link = invite("admin@example.com", "alice@example.com");
        mvc.perform(
                        post("/api/auth/invitation/preview")
                                .session(session)
                                .cookie(cookie)
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        "{\"id\":\""
                                                + link.id()
                                                + "\",\"token\":\""
                                                + link.token()
                                                + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"));
        assertThat(session.isInvalid()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM REFRESH_SESSION", Integer.class))
                .isZero();
        assertThat(identity.current("admin@example.com").accessRole()).isEqualTo("ADMIN");
        assertThat(identity.credentials("alice@example.com")).isNull();
    }

    @Test
    void failedSessionEstablishmentLeavesAcceptedAccountAndRetryRequiresPasswordLogin()
            throws Exception {
        var link = invite("admin@example.com", "alice@example.com");
        doThrow(new IllegalStateException("Session unavailable"))
                .when(sessionLogin)
                .establish(any(), any(), any());
        mvc.perform(
                        post("/api/auth/invitation/accept")
                                .with(csrf())
                                .contentType("application/json")
                                .content(body(link)))
                .andExpect(status().isInternalServerError());
        assertThat(identity.current("alice@example.com").accessRole()).isEqualTo("MEMBER");
        rejected(link, "INVITATION_USED");
        reset(sessionLogin);
        mvc.perform(
                        post("/api/auth/login")
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        "{\"email\":\"alice@example.com\",\"password\":\"Memberpass!\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void acceptedInvitationInvalidatesExistingVerificationTokenAndCompetingVerificationIsAtomic()
            throws Exception {
        identity.register(
                new RegistrationRequest(
                        "Pending",
                        "https://pending.com",
                        "Engineering",
                        "Alice",
                        "alice@example.com",
                        "Oldpassword!",
                        "OTHER"));
        var accountId =
                jdbc.queryForObject(
                        "SELECT ID FROM ACCOUNT WHERE EMAIL='alice@example.com'", UUID.class);
        var attempt = delivery.claim(accountId);
        var token = "v".repeat(43);
        delivery.accepted(attempt, token, "<verification@test.local>");
        var link = invite("admin@example.com", "alice@example.com");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var accepted =
                    pool.submit(
                            () -> {
                                start.await();
                                try {
                                    acceptance.accept(registration(link));
                                    return true;
                                } catch (IdentityException e) {
                                    assertThat(e.code()).isEqualTo("OTHER_COMPANY_MEMBER");
                                    return false;
                                }
                            });
            var verified =
                    pool.submit(
                            () -> {
                                start.await();
                                try {
                                    identity.verify(accountId, token);
                                    return true;
                                } catch (IdentityException e) {
                                    assertThat(e.code()).isEqualTo("INVALID_VERIFICATION");
                                    return false;
                                }
                            });
            start.countDown();
            assertThat(accepted.get(15, TimeUnit.SECONDS))
                    .isNotEqualTo(verified.get(15, TimeUnit.SECONDS));
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ACCOUNT WHERE EMAIL='alice@example.com'",
                                Integer.class))
                .isEqualTo(1);
    }
}
