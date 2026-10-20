package com.betterf.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.betterf.identity.api.dto.*;
import com.betterf.identity.api.exception.IdentityException;
import com.betterf.identity.api.service.*;
import com.betterf.identity.internal.service.*;
import com.betterf.knowledge.api.dto.*;
import com.betterf.knowledge.api.exception.KnowledgeException;
import com.betterf.knowledge.api.service.KnowledgeService;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Testcontainers
@SpringBootTest(
        properties = {
            "betterf.registration.delivery.enabled=false",
            "betterf.knowledge.classification.enabled=false"
        })
@AutoConfigureMockMvc
class IndividualIdentityIntegrationTests {
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
    SocialIdentityService social;
    @Autowired
    KnowledgeService knowledge;
    @Autowired
    InvitationAcceptanceService acceptance;
    @Autowired
    InvitationTransactions invitations;
    @Autowired
    VerificationEmailWorker worker;
    @Autowired
    PendingRegistrationCleanup cleanup;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MockMvc mvc;
    @Autowired
    JsonMapper json;
    @MockitoBean
    VerificationMail mail;
    @MockitoBean
    Clock clock;
    final Instant now = Instant.parse("2026-10-21T00:00:00Z");
    final Map<String, Link> links = new ConcurrentHashMap<>();

    record Link(UUID id, String token) {}

    final String password = "Validpass!";

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE ACCOUNT, ORGANIZATION, REFRESH_SESSION CASCADE");
        when(clock.instant()).thenReturn(now);
        doAnswer(
                        call -> {
                            links.put(
                                    call.getArgument(0),
                                    new Link(call.getArgument(1), call.getArgument(2)));
                            return "test-message";
                        })
                .when(mail)
                .send(anyString(), any(), anyString());
    }

    IndividualRegistrationRequest request(String email) {
        return new IndividualRegistrationRequest("Personal User", email, password);
    }

    SocialIdentityView provider(String provider, String subject, String email, Boolean verified) {
        return new SocialIdentityView(provider, subject, email, "Social User", verified);
    }

    void activate(String email) {
        identity.registerIndividual(request(email));
        worker.runOnce();
        var link = links.get(email.toLowerCase(Locale.ROOT));
        identity.verify(link.id(), link.token());
    }

    UUID organization() {
        identity.register(
                new RegistrationRequest(
                        "Example",
                        "https://example.com",
                        "Software",
                        "Admin",
                        "admin@example.com",
                        password,
                        "OTHER"));
        worker.runOnce();
        var link = links.get("admin@example.com");
        return identity.verify(link.id(), link.token()).account().organizationId();
    }

    void code(Runnable operation, String code) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        IdentityException.class,
                        failure -> assertThat(failure.code()).isEqualTo(code));
    }

    @Test
    void normalizationPreservesLegitimateDomainCharacters() {
        var account =
                social.authenticate(provider("linkedin", "gov-user", " Person@example.gov ", true));
        assertThat(account.email()).isEqualTo("person@example.gov");
        code(() -> identity.resend(account.email()), "EMAIL_IN_USE");
    }

    @Test
    void socialOnlyAccountsRejectPasswordLoginWithoutServerErrors() throws Exception {
        social.authenticate(provider("facebook", "social-only", "person@example.com", null));
        mvc.perform(
                        post("/api/auth/login")
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "email",
                                                        "person@example.com",
                                                        "password",
                                                        password))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordSignupCreatesNoOrganizationAndGatesAccessUntilVerification() throws Exception {
        mvc.perform(
                        post("/api/registration/individual")
                                .with(csrf())
                                .contentType("application/json")
                                .content(json.writeValueAsString(request("Person@Example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("person@example.com"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ORGANIZATION", Long.class)).isZero();
        assertThat(identity.credentials("PERSON@example.com").active()).isFalse();
        code(() -> identity.current("person@example.com"), "ACCESS_DENIED");
        mvc.perform(
                        post("/api/auth/login")
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "email",
                                                        "person@example.com",
                                                        "password",
                                                        password))))
                .andExpect(status().isUnauthorized());
        worker.runOnce();
        var link = links.get("person@example.com");
        var account = identity.verify(link.id(), link.token()).account();
        assertThat(account.organizationId()).isNull();
        assertThat(account.accessRole()).isEqualTo("INDIVIDUAL");
        assertThat(identity.verify(link.id(), link.token()).account()).isNull();
        assertThat(identity.credentials("person@example.com").active()).isTrue();
        mvc.perform(
                        post("/api/registration/individual")
                                .contentType("application/json")
                                .content(json.writeValueAsString(request("other@example.com"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void verificationResendExpiryAndRetentionApplyToIndividuals() {
        identity.registerIndividual(request("person@example.com"));
        worker.runOnce();
        var old = links.get("person@example.com");
        code(() -> identity.resend("person@example.com"), "RESEND_COOLDOWN");
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        identity.resend("person@example.com");
        worker.runOnce();
        var replacement = links.get("person@example.com");
        assertThat(replacement.token()).isNotEqualTo(old.token());
        code(() -> identity.verify(old.id(), old.token()), "INVALID_VERIFICATION");
        when(clock.instant()).thenReturn(now.plus(Duration.ofDays(2)));
        code(() -> identity.verify(replacement.id(), replacement.token()), "EXPIRED_VERIFICATION");
        when(clock.instant()).thenReturn(now.plus(Duration.ofDays(31)));
        cleanup.deleteAbandoned();
        assertThat(identity.credentials("person@example.com")).isNull();
    }

    @Test
    void allAccountKindsShareNormalizedEmailAndIndividualsCannotConvert() {
        organization();
        code(() -> identity.registerIndividual(request("ADMIN@example.com")), "EMAIL_IN_USE");
        activate("person@example.com");
        code(() -> identity.registerIndividual(request("PERSON@example.com")), "EMAIL_IN_USE");
        code(
                () ->
                        identity.register(
                                new RegistrationRequest(
                                        "Other",
                                        "https://other.com",
                                        "Software",
                                        "Other",
                                        "person@example.com",
                                        password,
                                        "OTHER")),
                "EMAIL_IN_USE");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE ACCOUNT SET ACCOUNT_TYPE='ORGANIZATION',"
                                            + " ORGANIZATION_ID=(SELECT ID FROM ORGANIZATION LIMIT"
                                            + " 1), ACCESS_ROLE='MEMBER' WHERE"
                                            + " EMAIL='person@example.com'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE ACCOUNT SET EMAIL=' Person@example.com ' WHERE"
                                                + " EMAIL='person@example.com'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void socialVerificationRulesAndRetriesAreAtomic() {
        var verified = provider("linkedin", "one", "Person@Example.com", true);
        var account = social.authenticate(verified);
        assertThat(social.authenticate(verified).id()).isEqualTo(account.id());
        assertThat(
                        social.authenticate(provider("facebook", "two", "other@example.com", null))
                                .organizationId())
                .isNull();
        code(
                () ->
                        social.authenticate(
                                provider("linkedin", "three", "unverified@example.com", false)),
                "PROVIDER_EMAIL_UNVERIFIED");
        code(
                () -> social.authenticate(provider("facebook", "four", null, null)),
                "PROVIDER_EMAIL_MISSING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM OAUTH_IDENTITY", Long.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACCOUNT", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM VERIFICATION_EMAIL", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ORGANIZATION", Long.class)).isZero();
    }

    @Test
    void linkingRequiresExistingIdentityAndCannotReassignProvider() {
        activate("person@example.com");
        activate("other@example.com");
        var provider = provider("linkedin", "one", "person@example.com", true);
        code(() -> social.authenticate(provider), "LINK_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM OAUTH_IDENTITY", Long.class)).isZero();
        code(() -> social.link("other@example.com", provider), "LINK_ACCOUNT_MISMATCH");
        var account = social.link("person@example.com", provider);
        assertThat(social.authenticate(provider).id()).isEqualTo(account.id());
        assertThat(identity.current("person@example.com").id()).isEqualTo(account.id());
        assertThat(social.link("person@example.com", provider).id()).isEqualTo(account.id());
        code(
                () ->
                        social.link(
                                "other@example.com",
                                provider("linkedin", "one", "other@example.com", true)),
                "PROVIDER_ALREADY_LINKED");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO"
                                            + " OAUTH_IDENTITY(ID,ACCOUNT_ID,PROVIDER,SUBJECT_ID)"
                                            + " VALUES (?,?,?,?)",
                                        UUID.randomUUID(),
                                        account.id(),
                                        "linkedin",
                                        "one"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentProviderAndPasswordRegistrationsCreateOneIdentity() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<?>>();
            for (int index = 0; index < 4; index++) {
                final int operation = index;
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        if (operation % 2 == 0)
                                            identity.registerIndividual(
                                                    request("race@example.com"));
                                        else
                                            social.authenticate(
                                                    provider(
                                                            "facebook",
                                                            "same",
                                                            "race@example.com",
                                                            null));
                                    } catch (IdentityException expected) {
                                        assertThat(expected.code())
                                                .isIn("EMAIL_IN_USE", "LINK_REQUIRED");
                                    }
                                    return null;
                                }));
            }
            start.countDown();
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACCOUNT", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM OAUTH_IDENTITY", Long.class))
                .isLessThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ORGANIZATION", Long.class)).isZero();
    }

    @Test
    void personalCreationBrowsingAndViewingAreIsolatedFromEveryOtherScope() {
        organization();
        activate("person@example.com");
        activate("other@example.com");
        var content =
                new TicketContent(
                        "Private knowledge",
                        "A useful insight",
                        "Coding",
                        List.of(
                                new TicketContent.DetailBlock(
                                        "code", "return key;", "java", null)));
        var request = new CreateTicketRequest(UUID.randomUUID(), content);
        var personal = knowledge.create("person@example.com", request);
        var company =
                knowledge.create(
                        "admin@example.com", new CreateTicketRequest(UUID.randomUUID(), content));
        assertThat(knowledge.create("person@example.com", request).id()).isEqualTo(personal.id());
        assertThat(knowledge.get("person@example.com", personal.id()).id())
                .isEqualTo(personal.id());
        assertThat(knowledge.browse("person@example.com", null, 20, null).items())
                .extracting(TicketSummary::id)
                .containsExactly(personal.id());
        assertThat(knowledge.browse("other@example.com", null, 20, null).items()).isEmpty();
        assertThat(knowledge.browse("admin@example.com", null, 20, null).items())
                .extracting(TicketSummary::id)
                .containsExactly(company.id());
        assertThatThrownBy(() -> knowledge.get("other@example.com", personal.id()))
                .isInstanceOf(KnowledgeException.class);
        assertThatThrownBy(() -> knowledge.get("admin@example.com", personal.id()))
                .isInstanceOf(KnowledgeException.class);
        assertThatThrownBy(() -> knowledge.get("person@example.com", company.id()))
                .isInstanceOf(KnowledgeException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE KNOWLEDGE_TICKET SET ORGANIZATION_ID=(SELECT ID"
                                                + " FROM ORGANIZATION LIMIT 1) WHERE ID=?",
                                        personal.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pendingAndActiveIndividualsCannotReceiveOrAcceptInvitations() {
        organization();
        // An invitation can predate individual registration; acceptance must recheck
        // classification.
        String token = "a".repeat(43);
        var invitation =
                invitations
                        .claim(
                                "admin@example.com",
                                new InvitationRequest("person@example.com"),
                                token)
                        .view();
        invitations.complete(invitation.id(), token, "smtp-id", null);
        identity.registerIndividual(request("person@example.com"));
        code(
                () ->
                        invitations.claim(
                                "admin@example.com",
                                new InvitationRequest("person@example.com"),
                                token),
                "ACCOUNT_INELIGIBLE");
        var link = new InvitationLinkRequest(invitation.id(), token);
        assertThat(acceptance.preview(link).status()).isEqualTo("ACCOUNT_INELIGIBLE");
        code(
                () ->
                        acceptance.accept(
                                new MemberRegistrationRequest(link, "Personal", "OTHER", password)),
                "ACCOUNT_INELIGIBLE");
        worker.runOnce();
        var verify = links.get("person@example.com");
        identity.verify(verify.id(), verify.token());
        code(
                () ->
                        invitations.claim(
                                "person@example.com",
                                new InvitationRequest("someone@example.com"),
                                token),
                "ACCESS_DENIED");
        code(
                () ->
                        invitations.claim(
                                "admin@example.com",
                                new InvitationRequest("person@example.com"),
                                token),
                "ACCOUNT_INELIGIBLE");
    }

    @Test
    void sessionLinkRequiresNewTrustedAuthenticationAndCsrf() throws Exception {
        activate("person@example.com");
        var session = new MockHttpSession();
        var servletRequest = new org.springframework.mock.web.MockHttpServletRequest();
        servletRequest.setSession(session);
        var provider = provider("linkedin", "one", "person@example.com", true);
        com.betterf.foundation.internal.security.SocialLoginState.pending(
                servletRequest, provider, now);
        mvc.perform(
                        post("/api/auth/social/link")
                                .session(session)
                                .with(user("person@example.com"))
                                .with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/auth/login")
                                .session(session)
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "email",
                                                        "person@example.com",
                                                        "password",
                                                        password))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/social/link").session(session))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/social/link").session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("person@example.com"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM OAUTH_IDENTITY", Long.class))
                .isEqualTo(1);
        mvc.perform(post("/api/auth/social/link").session(session).with(csrf()))
                .andExpect(status().isUnauthorized());
    }
}
