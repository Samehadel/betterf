package com.betterf.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.betterf.identity.api.dto.RegistrationRequest;
import com.betterf.identity.api.exception.EmailDeliveryException;
import com.betterf.identity.api.exception.IdentityException;
import com.betterf.identity.api.service.IdentityService;
import com.betterf.identity.internal.service.PendingRegistrationCleanup;
import com.betterf.identity.internal.service.VerificationMail;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class RegistrationIntegrationTests {
    @Container
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:17.6-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }

    @Autowired IdentityService identity;
    @Autowired PendingRegistrationCleanup cleanup;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @MockitoBean VerificationMail mail;
    @MockitoBean Clock clock;
    final Instant start = Instant.parse("2026-10-04T12:00:00Z");
    final Map<String, Link> links = new ConcurrentHashMap<>();

    record Link(UUID id, String token) {}

    final String password = "a long test passphrase";

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE VERIFICATION_EMAIL, ACCOUNT, ORGANIZATION CASCADE");
        when(clock.instant()).thenReturn(start);
        doAnswer(
                        call -> {
                            links.put(
                                    call.getArgument(0),
                                    new Link(call.getArgument(1), call.getArgument(2)));
                            return null;
                        })
                .when(mail)
                .send(anyString(), any(), anyString());
    }

    RegistrationRequest request(String email, String website) {
        return new RegistrationRequest(
                "Acme",
                "https://" + website,
                "Engineering",
                "Ada Example",
                email,
                password,
                "OTHER");
    }

    void register(String email, String domain) {
        identity.register(request(email, domain));
    }

    void verify(String email) {
        var link = links.get(email);
        identity.verify(link.id(), link.token());
    }

    int activeOrganizations() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ORGANIZATION WHERE STATUS='ACTIVE'", Integer.class);
    }

    int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @Test
    void registrationIsPendingAndPasswordIsHashedWithReusableRoles() throws Exception {
        var body = request("Ada@elsewhere.com", "Company.CO.UK");
        mvc.perform(
                        post("/api/registration")
                                .with(csrf())
                                .contentType("application/json")
                                .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("ada@elsewhere.com"));
        assertThat(activeOrganizations()).isZero();
        assertThat(count("ACCOUNT")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT STATUS FROM ACCOUNT", String.class))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT STATUS FROM ORGANIZATION", String.class))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT PASSWORD_HASH FROM ACCOUNT", String.class))
                .doesNotContain(password);
        assertThat(jdbc.queryForObject("SELECT TOKEN_HASH FROM VERIFICATION_EMAIL", String.class))
                .doesNotContain(links.get("ada@elsewhere.com").token());
        assertThat(jdbc.queryForObject("SELECT DOMAIN FROM ORGANIZATION", String.class))
                .isEqualTo("company.co.uk");
        mvc.perform(get("/api/registration/roles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(25));
        mvc.perform(
                        post("/api/auth/login")
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "email",
                                                        "ada@elsewhere.com",
                                                        "password",
                                                        password))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("LOGIN_FAILED"));
        mvc.perform(get("/api/auth/me")).andExpect(status().isForbidden());
        mvc.perform(post("/api/invitations").with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void verifyCreatesMembershipAndPasswordLoginSessionLogoutWork() throws Exception {
        register("ada@example.com", "company.com");
        var link = links.get("ada@example.com");
        mvc.perform(
                        post("/api/registration/verify")
                                .with(csrf())
                                .contentType("application/json")
                                .content(json.writeValueAsString(link)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VERIFIED"));
        assertThat(activeOrganizations()).isEqualTo(1);
        assertThat(identity.current("ada@example.com").accessRole()).isEqualTo("ADMIN");
        assertThat(identity.current("ada@example.com").professionalRole()).isEqualTo("OTHER");
        assertThat(identity.verify(link.id(), link.token()).status()).isEqualTo("ALREADY_VERIFIED");
        var old = new MockHttpSession();
        String oldId = old.getId();
        var response =
                mvc.perform(
                                post("/api/auth/login")
                                        .session(old)
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "email",
                                                                "ADA@example.com",
                                                                "password",
                                                                password))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.organizationName").value("Acme"))
                        .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                        .andReturn();
        var session = (MockHttpSession) response.getRequest().getSession(false);
        assertThat(session.getId()).isNotEqualTo(oldId);
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("ada@example.com"));
        mvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isForbidden());
    }

    @Test
    void publicMutationsRequireCsrfAndMaskedTokenWorks() throws Exception {
        mvc.perform(
                        post("/api/registration")
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                request("ada@example.com", "company.com"))))
                .andExpect(status().isForbidden());
        var response = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var data = json.readTree(response.getResponse().getContentAsString()).path("data");
        mvc.perform(
                        post("/api/registration")
                                .session((MockHttpSession) response.getRequest().getSession(false))
                                .header(
                                        data.path("headerName").asText(),
                                        data.path("token").asText())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                request("ada@example.com", "company.com"))))
                .andExpect(status().isOk());
    }

    @Test
    void validationIsActionableAndNoPartialDataIsSaved() throws Exception {
        mvc.perform(
                        post("/api/registration")
                                .with(csrf())
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.error.message")
                                .value(
                                        org.hamcrest.Matchers.containsString(
                                                "Company name is required")));
        var bad =
                new RegistrationRequest(
                        "Acme",
                        "https://company.com",
                        "IT",
                        "Ada",
                        "ada@example.com",
                        "short",
                        "OTHER");
        assertThatThrownBy(() -> identity.register(bad))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("15 to 128");
        var badRole =
                new RegistrationRequest(
                        "Acme",
                        "https://company.com",
                        "IT",
                        "Ada",
                        "ada@example.com",
                        password,
                        "SUPERADMIN");
        assertThatThrownBy(() -> identity.register(badRole))
                .hasMessageContaining("professional role");
        assertThat(count("ACCOUNT")).isZero();
        verifyNoInteractions(mail);
    }

    @Test
    void duplicatesCheckDomainAndAnyAccountMembershipNotCompanyName() {
        register("ada@example.com", "company.com");
        verify("ada@example.com");
        assertThatThrownBy(() -> register("other@example.com", "COMPANY.com"))
                .hasMessage("You cannot register this company. It is already registered.");
        jdbc.update("UPDATE ACCOUNT SET ACCESS_ROLE='MEMBER'");
        assertThatThrownBy(() -> register("ADA@example.com", "different.com"))
                .hasMessage("You cannot register this company. It is already registered.");
        register("other@example.com", "different.com");
        verify("other@example.com");
        assertThat(activeOrganizations()).isEqualTo(2);
    }

    @Test
    void repeatedRegistrationDoesNotChangeCredentialsOrSendAgain() {
        register("ada@example.com", "company.com");
        var original = links.get("ada@example.com");
        identity.register(
                new RegistrationRequest(
                        "Attacker",
                        "https://other.com",
                        "Wrong",
                        "Wrong",
                        "ADA@example.com",
                        "another long password",
                        "SOFTWARE_ENGINEER"));
        assertThat(count("ACCOUNT")).isEqualTo(1);
        org.mockito.Mockito.verify(mail, times(1)).send(anyString(), any(), anyString());
        assertThat(links.get("ada@example.com")).isEqualTo(original);
        verify("ada@example.com");
        assertThat(identity.current("ada@example.com").organizationName()).isEqualTo("Acme");
    }

    @Test
    void cooldownDoesNotInvalidateAndResendRotatesSameRecord() {
        register("ada@example.com", "company.com");
        var first = links.get("ada@example.com");
        when(clock.instant()).thenReturn(start.plusSeconds(59));
        assertThatThrownBy(() -> identity.resend("ada@example.com"))
                .isInstanceOfSatisfying(
                        IdentityException.class,
                        e -> assertThat(e.code()).isEqualTo("RESEND_COOLDOWN"));
        assertThat(links.get("ada@example.com")).isEqualTo(first);
        org.mockito.Mockito.verify(mail, times(1)).send(anyString(), any(), anyString());
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        identity.resend("ada@example.com");
        var second = links.get("ada@example.com");
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThatThrownBy(() -> identity.verify(first.id(), first.token()))
                .hasMessageContaining("replaced");
        when(clock.instant()).thenReturn(start.plus(Duration.ofHours(24)).plusSeconds(59));
        verify("ada@example.com");
        assertThat(count("ACCOUNT")).isEqualTo(1);
        assertThat(activeOrganizations()).isEqualTo(1);
    }

    @Test
    void earlyResendLeavesOriginalLinkUsable() {
        register("ada@example.com", "company.com");
        assertThatThrownBy(() -> identity.resend("ada@example.com"))
                .isInstanceOf(IdentityException.class);
        verify("ada@example.com");
        assertThat(activeOrganizations()).isEqualTo(1);
    }

    @Test
    void exactlyTwentyFourHoursIsExpiredAndInvalidTokenCannotActivate() {
        register("ada@example.com", "company.com");
        var link = links.get("ada@example.com");
        assertThatThrownBy(() -> identity.verify(link.id(), "X".repeat(43)))
                .hasMessageContaining("invalid");
        when(clock.instant()).thenReturn(start.plus(Duration.ofHours(24)));
        assertThatThrownBy(() -> identity.verify(link.id(), link.token()))
                .hasMessageContaining("expired");
        assertThat(activeOrganizations()).isZero();
        identity.resend("ada@example.com");
        verify("ada@example.com");
        assertThat(activeOrganizations()).isEqualTo(1);
    }

    @Test
    void failedEmailDeliveryPersistsFailureAndPreservesPreviousTokenOnResend() {
        doThrow(new EmailDeliveryException()).when(mail).send(anyString(), any(), anyString());
        assertThatThrownBy(() -> register("ada@example.com", "company.com"))
                .hasMessageContaining("could not send");
        assertThat(count("ACCOUNT")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT STATUS FROM VERIFICATION_EMAIL", String.class))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT TOKEN_HASH FROM VERIFICATION_EMAIL", String.class))
                .isNull();
        reset(mail);
        doAnswer(
                        call -> {
                            links.put(
                                    call.getArgument(0),
                                    new Link(call.getArgument(1), call.getArgument(2)));
                            return null;
                        })
                .when(mail)
                .send(anyString(), any(), anyString());
        identity.resend("ada@example.com");
        var first = links.get("ada@example.com");
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        doThrow(new EmailDeliveryException()).when(mail).send(anyString(), any(), anyString());
        assertThatThrownBy(() -> identity.resend("ada@example.com"))
                .hasMessageContaining("could not send");
        assertThat(jdbc.queryForObject("SELECT STATUS FROM VERIFICATION_EMAIL", String.class))
                .isEqualTo("FAILED");
        identity.verify(first.id(), first.token());
        assertThat(activeOrganizations()).isEqualTo(1);
    }

    @Test
    void competingVerificationsCreateExactlyOneCompanyAndDoNotActivateLoser() throws Exception {
        register("one@example.com", "company.co.uk");
        register("two@example.com", "company.co.uk");
        assertThat(activeOrganizations()).isZero();
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> results = new ArrayList<>();
            for (String email : List.of("one@example.com", "two@example.com"))
                results.add(
                        pool.submit(
                                () -> {
                                    barrier.await(10, TimeUnit.SECONDS);
                                    try {
                                        verify(email);
                                        return "OK";
                                    } catch (IdentityException exception) {
                                        return exception.code();
                                    }
                                }));
            assertThat(
                            List.of(
                                    results.get(0).get(15, TimeUnit.SECONDS),
                                    results.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "DUPLICATE_REGISTRATION");
        }
        assertThat(activeOrganizations()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ACCOUNT WHERE STATUS='ACTIVE'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ACCOUNT WHERE STATUS='PENDING' AND"
                                    + " ACCESS_ROLE IS NULL",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void simultaneousRegistrationsAndResendsDoNotDuplicateAccountsOrEmails() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> register("ada@example.com", "company.com"));
            var b = pool.submit(() -> register("ADA@example.com", "company.com"));
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
            assertThat(count("ACCOUNT")).isEqualTo(1);
            org.mockito.Mockito.verify(mail, times(1)).send(anyString(), any(), anyString());
            when(clock.instant()).thenReturn(start.plusSeconds(60));
            Callable<String> resend =
                    () -> {
                        try {
                            identity.resend("ada@example.com");
                            return "OK";
                        } catch (IdentityException e) {
                            return e.code();
                        }
                    };
            var c = pool.submit(resend);
            var d = pool.submit(resend);
            assertThat(List.of(c.get(15, TimeUnit.SECONDS), d.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "RESEND_COOLDOWN");
            org.mockito.Mockito.verify(mail, times(2)).send(anyString(), any(), anyString());
        }
    }

    @Test
    void accountAndOrganizationMustBothBeActiveForAccess() throws Exception {
        register("ada@example.com", "company.com");
        verify("ada@example.com");
        jdbc.update("UPDATE ORGANIZATION SET STATUS='PENDING'");
        assertThat(identity.credentials("ada@example.com").active()).isFalse();
        assertThatThrownBy(() -> identity.current("ada@example.com"))
                .hasMessageContaining("Verify your email");
        mvc.perform(
                        post("/api/auth/login")
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "email",
                                                        "ada@example.com",
                                                        "password",
                                                        password))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void statusVocabularyCanEvolveWithoutChangingDatabaseConstraints() {
        register("ada@example.com", "company.com");
        // A future application version can introduce statuses without altering these columns.
        assertThat(jdbc.update("UPDATE ORGANIZATION SET STATUS='BLOCKED'")).isEqualTo(1);
        assertThat(jdbc.update("UPDATE ACCOUNT SET STATUS='SUSPENDED'")).isEqualTo(1);
        assertThat(jdbc.update("UPDATE VERIFICATION_EMAIL SET STATUS='DEFERRED'")).isEqualTo(1);
    }

    @Test
    void pendingRetentionDoesNotDeleteActiveAccounts() {
        register("active@example.com", "first.com");
        verify("active@example.com");
        register("pending@example.com", "second.com");
        when(clock.instant()).thenReturn(start.plus(Duration.ofDays(31)));
        cleanup.deleteAbandoned();
        assertThat(count("ACCOUNT")).isEqualTo(1);
        assertThat(identity.current("active@example.com").email()).isEqualTo("active@example.com");
    }
}
