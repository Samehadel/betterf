package com.betterf.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.betterf.knowledge.api.dto.*;
import com.betterf.knowledge.api.exception.KnowledgeException;
import com.betterf.knowledge.api.service.KnowledgeService;
import com.betterf.knowledge.internal.service.*;

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

import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.concurrent.*;

@Testcontainers
@SpringBootTest(
        properties = {
            "management.health.mail.enabled=false",
            "betterf.registration.delivery.enabled=false",
            "betterf.knowledge.classification.enabled=false"
        })
@AutoConfigureMockMvc
class KnowledgeIntegrationTests {
    @Container
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:17.6-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }

    @Autowired KnowledgeService knowledge;
    @Autowired ClassificationWorker worker;
    @Autowired ClassificationTransactions transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @MockitoBean KnowledgeAi ai;
    UUID organization;
    UUID author;

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE KNOWLEDGE_TICKET, ACCOUNT, ORGANIZATION CASCADE");
        organization = UUID.randomUUID();
        author = seed("member@example.com", "MEMBER", organization);
        seed("admin@example.com", "ADMIN", organization);
        seed("outsider@example.com", "MEMBER", UUID.randomUUID());
    }

    UUID seed(String email, String role, UUID org) {
        jdbc.update(
                "INSERT INTO ORGANIZATION (ID, NAME, DOMAIN, SPECIALIZATION, STATUS) VALUES (?,"
                    + " 'Private organization', ?, 'Engineering', 'ACTIVE') ON CONFLICT DO NOTHING",
                org,
                org + ".example");
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO ACCOUNT (ID, EMAIL, FULL_NAME, PASSWORD_HASH, PROFESSIONAL_ROLE,"
                        + " STATUS, ORGANIZATION_ID, ACCESS_ROLE, UPDATED_AT) VALUES (?, ?, 'Ada',"
                        + " 'unused', 'OTHER', 'ACTIVE', ?, ?, CURRENT_TIMESTAMP)",
                id,
                email,
                org,
                role);
        return id;
    }

    TicketContent content() {
        return new TicketContent(
                "Retry safely",
                "Reuse the same idempotency key.",
                "Coding",
                List.of(
                        new TicketContent.DetailBlock("heading", "Avoid duplicates", null, null),
                        new TicketContent.DetailBlock("code", "return key;", "java", null)));
    }

    CreateTicketRequest request() {
        return new CreateTicketRequest(UUID.randomUUID(), content());
    }

    @Test
    void publishesImmediatelyForMemberAndAdminAndEnforcesTenantAndActiveMembership() {
        var ticket = knowledge.create("member@example.com", request());
        assertThat(ticket.authorId()).isEqualTo(author);
        assertThat(ticket.createdAt()).isNotNull();
        assertThat(ticket.classificationStatus()).isEqualTo("PENDING");
        assertThat(ticket.content()).isEqualTo(content());
        assertThat(knowledge.get("admin@example.com", ticket.id()).id()).isEqualTo(ticket.id());
        assertThat(knowledge.create("admin@example.com", request())).isNotNull();
        assertThatThrownBy(() -> knowledge.get("outsider@example.com", ticket.id()))
                .isInstanceOfSatisfying(
                        KnowledgeException.class, e -> assertThat(e.status()).isEqualTo(404));
        jdbc.update("UPDATE ACCOUNT SET STATUS='PENDING' WHERE ID=?", author);
        assertThatThrownBy(() -> knowledge.create("member@example.com", request()))
                .hasMessageContaining("Verify your email");
        jdbc.update("UPDATE ACCOUNT SET STATUS='ACTIVE' WHERE ID=?", author);
        jdbc.update("UPDATE ORGANIZATION SET STATUS='PENDING' WHERE ID=?", organization);
        assertThatThrownBy(() -> knowledge.get("member@example.com", ticket.id()))
                .hasMessageContaining("Verify your email");
    }

    @Test
    void concurrentRetriesPublishOnlyOnceAndChangedPayloadIsRejected() throws Exception {
        var request = request();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> knowledge.create("member@example.com", request));
            var two = pool.submit(() -> knowledge.create("member@example.com", request));
            assertThat(one.get(10, TimeUnit.SECONDS).id())
                    .isEqualTo(two.get(10, TimeUnit.SECONDS).id());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM KNOWLEDGE_TICKET", Integer.class))
                .isEqualTo(1);
        var different =
                new TicketContent("Changed", content().capture(), "Coding", content().details());
        assertThatThrownBy(
                        () ->
                                knowledge.create(
                                        "member@example.com",
                                        new CreateTicketRequest(request.submissionId(), different)))
                .isInstanceOfSatisfying(
                        KnowledgeException.class, e -> assertThat(e.status()).isEqualTo(409));
    }

    @Test
    void classificationIsSeparateAndPersistsOnlyValidatedCatalogSelections() {
        var ticket = knowledge.create("member@example.com", request());
        // Make the job due independently of host/container clock precision.
        jdbc.update(
                "UPDATE KNOWLEDGE_TICKET SET NEXT_ATTEMPT_AT=CURRENT_TIMESTAMP-INTERVAL '1 second'"
                    + " WHERE ID=?",
                ticket.id());
        verifyNoInteractions(ai);
        when(ai.classify(content()))
                .thenReturn(List.of("area:backend", "type:coding", "area:backend"));
        worker.runOnce();
        var classified = knowledge.get("member@example.com", ticket.id());
        assertThat(classified.classificationStatus()).isEqualTo("COMPLETE");
        assertThat(classified.attributes()).containsExactly("area:backend", "type:coding");
    }

    @Test
    void invalidOutputAndProviderFailureRetryThreeTimesWithoutUnpublishing() {
        var ticket = knowledge.create("member@example.com", request());
        // Make the job due independently of host/container clock precision.
        jdbc.update(
                "UPDATE KNOWLEDGE_TICKET SET NEXT_ATTEMPT_AT=CURRENT_TIMESTAMP-INTERVAL '1 second'"
                    + " WHERE ID=?",
                ticket.id());
        when(ai.classify(content())).thenReturn(List.of("invented:attribute"));
        worker.runOnce();
        assertThat(knowledge.get("member@example.com", ticket.id()).classificationStatus())
                .isEqualTo("RETRYING");
        when(ai.classify(content())).thenThrow(new IllegalStateException("provider unavailable"));
        for (int i = 0; i < 2; i++) {
            jdbc.update(
                    "UPDATE KNOWLEDGE_TICKET SET NEXT_ATTEMPT_AT=CURRENT_TIMESTAMP-INTERVAL '1"
                            + " second'");
            worker.runOnce();
        }
        var published = knowledge.get("member@example.com", ticket.id());
        assertThat(published.classificationStatus()).isEqualTo("FAILED");
        assertThat(published.content()).isEqualTo(content());
        assertThat(published.attributes()).isEmpty();
        worker.runOnce();
        verify(ai, times(3)).classify(content());
    }

    @Test
    void leaseRecoveryFencesStaleWorkersAndEmptyResultsComplete() {
        var ticket = knowledge.create("member@example.com", request());
        // Make the job due independently of host/container clock precision.
        jdbc.update(
                "UPDATE KNOWLEDGE_TICKET SET NEXT_ATTEMPT_AT=CURRENT_TIMESTAMP-INTERVAL '1 second'"
                    + " WHERE ID=?",
                ticket.id());
        var oldWork = transactions.claim();
        assertThat(oldWork).isNotNull();
        assertThat(transactions.claim()).isNull();
        jdbc.update(
                "UPDATE KNOWLEDGE_TICKET SET LEASE_UNTIL=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        var newWork = transactions.claim();
        transactions.complete(oldWork, List.of("area:backend"));
        assertThat(knowledge.get("member@example.com", ticket.id()).classificationStatus())
                .isEqualTo("PROCESSING");
        transactions.complete(newWork, List.of());
        assertThat(knowledge.get("member@example.com", ticket.id()).classificationStatus())
                .isEqualTo("COMPLETE");
        assertThat(knowledge.get("member@example.com", ticket.id()).attributes()).isEmpty();
    }

    @Test
    void refinementTargetsOneFieldWithWholeTicketContextAndNeverMutatesPublication() {
        when(ai.refine(content(), "title")).thenReturn("Safe retries");
        var result =
                knowledge.refine("member@example.com", new RefinementRequest("title", content()));
        assertThat(result).isEqualTo(new RefinementView("title", "Safe retries"));
        verify(ai).refine(content(), "title");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM KNOWLEDGE_TICKET", Integer.class))
                .isZero();
        var withoutCapture =
                new TicketContent(content().title(), "", "Coding", content().details());
        when(ai.refine(withoutCapture, "capture")).thenReturn("Reuse a stable key.");
        assertThat(
                        knowledge.refine(
                                "member@example.com",
                                new RefinementRequest("capture", withoutCapture)))
                .isEqualTo(new RefinementView("capture", "Reuse a stable key."));
        when(ai.refine(content(), "title")).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(
                        () ->
                                knowledge.refine(
                                        "member@example.com",
                                        new RefinementRequest("title", content())))
                .isInstanceOfSatisfying(
                        KnowledgeException.class,
                        e -> assertThat(e.code()).isEqualTo("REFINEMENT_UNAVAILABLE"));
        assertThatThrownBy(
                        () ->
                                knowledge.refine(
                                        "member@example.com",
                                        new RefinementRequest("details", content())))
                .isInstanceOfSatisfying(
                        KnowledgeException.class, e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    void publishesWithOmittedCapture() {
        var withoutCapture =
                new TicketContent(content().title(), null, "Coding", content().details());
        var created =
                knowledge.create(
                        "member@example.com",
                        new CreateTicketRequest(UUID.randomUUID(), withoutCapture));
        assertThat(knowledge.get("member@example.com", created.id()).content().capture()).isNull();
    }

    @Test
    void refinementTransportReturnsOnlySelectedFieldAndRejectsDetails() throws Exception {
        when(ai.refine(content(), "capture")).thenReturn("Reuse a stable key.");
        mvc.perform(
                        post("/api/knowledge/tickets/refine")
                                .with(user("member@example.com").roles("MEMBER"))
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                new RefinementRequest("capture", content()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.field").value("capture"))
                .andExpect(jsonPath("$.data.text").value("Reuse a stable key."))
                .andExpect(jsonPath("$.data.title").doesNotExist());
        mvc.perform(
                        post("/api/knowledge/tickets/refine")
                                .with(user("member@example.com").roles("MEMBER"))
                                .with(csrf())
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                new RefinementRequest("details", content()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void transportWrapsKnowledgeEnforcesCsrfAndProvidesProtectedMetricsAndPublicProbes()
            throws Exception {
        mvc.perform(
                        post("/api/knowledge/tickets")
                                .with(user("member@example.com").roles("MEMBER"))
                                .with(csrf())
                                .contentType("application/json")
                                .content(json.writeValueAsString(request())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.classificationStatus").value("PENDING"));
        mvc.perform(
                        post("/api/knowledge/tickets")
                                .with(user("member@example.com").roles("MEMBER"))
                                .contentType("application/json")
                                .content(json.writeValueAsString(request())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/knowledge/tickets/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        for (var probe : List.of("liveness", "readiness"))
            mvc.perform(get("/actuator/health/" + probe)).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").with(user("member@example.com").roles("MEMBER")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/actuator/metrics/jvm.memory.used")
                                .with(user("admin@example.com").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("jvm.memory.used"));
        mvc.perform(get("/actuator/prometheus").with(user("admin@example.com").roles("ADMIN")))
                .andExpect(status().isOk());
        jdbc.update("UPDATE ACCOUNT SET STATUS='PENDING' WHERE EMAIL='admin@example.com'");
        mvc.perform(get("/actuator/metrics").with(user("admin@example.com").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }
}
