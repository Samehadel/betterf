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
    UUID workspace;
    UUID author;

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE KNOWLEDGE_TICKET, ACCOUNT, WORKSPACE CASCADE");
        workspace = UUID.randomUUID();
        author = seed("member@example.com", "MEMBER", workspace);
        seed("admin@example.com", "ADMIN", workspace);
        seed("outsider@example.com", "MEMBER", UUID.randomUUID());
    }

    UUID seed(String email, String role, UUID org) {
        jdbc.update(
                "INSERT INTO WORKSPACE (ID, NAME, STATUS) VALUES (?, 'Shared workspace', 'ACTIVE')"
                    + " ON CONFLICT DO NOTHING",
                org);
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO ACCOUNT (ID, EMAIL, FULL_NAME, PASSWORD_HASH, STATUS,"
                    + " DEFAULT_WORKSPACE_ID, UPDATED_AT) VALUES (?, ?, 'Ada', 'unused', 'ACTIVE',"
                    + " ?, CURRENT_TIMESTAMP)",
                id,
                email,
                org);
        jdbc.update(
                "INSERT INTO WORKSPACE_MEMBERSHIP (ID, ACCOUNT_ID, WORKSPACE_ID, ROLE) VALUES (?,"
                    + " ?, ?, ?)",
                UUID.randomUUID(),
                id,
                org,
                "ADMIN".equals(role) ? "OWNER" : "MEMBER");
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
        var ticket =
                knowledge.create(
                        "member@example.com", workspaceFor("member@example.com"), request());
        assertThat(ticket.authorId()).isEqualTo(author);
        assertThat(ticket.createdAt()).isNotNull();
        assertThat(ticket.classificationStatus()).isEqualTo("PENDING");
        assertThat(ticket.content()).isEqualTo(content());
        assertThat(
                        knowledge
                                .get(
                                        "admin@example.com",
                                        workspaceFor("admin@example.com"),
                                        ticket.id())
                                .id())
                .isEqualTo(ticket.id());
        assertThat(
                        knowledge.create(
                                "admin@example.com", workspaceFor("admin@example.com"), request()))
                .isNotNull();
        assertThatThrownBy(
                        () ->
                                knowledge.get(
                                        "outsider@example.com",
                                        workspaceFor("outsider@example.com"),
                                        ticket.id()))
                .isInstanceOfSatisfying(
                        KnowledgeException.class, e -> assertThat(e.status()).isEqualTo(404));
        jdbc.update("UPDATE ACCOUNT SET STATUS='PENDING' WHERE ID=?", author);
        assertThatThrownBy(
                        () ->
                                knowledge.create(
                                        "member@example.com",
                                        workspaceFor("member@example.com"),
                                        request()))
                .hasMessageContaining("Verify your email");
        jdbc.update("UPDATE ACCOUNT SET STATUS='ACTIVE' WHERE ID=?", author);
        jdbc.update("UPDATE WORKSPACE SET STATUS='PENDING' WHERE ID=?", workspace);
        assertThatThrownBy(
                        () ->
                                knowledge.get(
                                        "member@example.com",
                                        workspaceFor("member@example.com"),
                                        ticket.id()))
                .hasMessageContaining("access to this workspace");
    }

    @Test
    void browseUsesStableKeysetsAcrossInsertsAndClassificationChanges() {
        var published = new ArrayList<TicketView>();
        for (int i = 0; i < 25; i++)
            published.add(
                    knowledge.create(
                            "member@example.com", workspaceFor("member@example.com"), request()));
        knowledge.create("outsider@example.com", workspaceFor("outsider@example.com"), request());
        jdbc.update("UPDATE KNOWLEDGE_TICKET SET CREATED_AT='2026-10-01T12:00:00Z'");
        var first =
                knowledge.browse(
                        "admin@example.com", workspaceFor("admin@example.com"), null, null, null);
        assertThat(first.items()).hasSize(20);
        var expected =
                published.stream()
                        .map(t -> t.id().toString())
                        .sorted(Comparator.reverseOrder())
                        .toList();
        assertThat(first.items().stream().map(t -> t.id().toString()))
                .containsExactlyElementsOf(expected.subList(0, 20));
        var newest =
                knowledge.create(
                        "member@example.com", workspaceFor("member@example.com"), request());
        jdbc.update(
                "UPDATE KNOWLEDGE_TICKET SET CLASSIFICATION_STATUS='FAILED' WHERE ID=?",
                first.items().getFirst().id());
        var next =
                knowledge.browse(
                        "member@example.com",
                        workspaceFor("member@example.com"),
                        null,
                        20,
                        first.nextCursor());
        assertThat(next.items()).hasSize(5);
        assertThat(next.nextCursor()).isNull();
        assertThat(next.items().stream().map(t -> t.id().toString()))
                .containsExactlyElementsOf(expected.subList(20, 25));
        assertThat(
                        knowledge
                                .browse(
                                        "member@example.com",
                                        workspaceFor("member@example.com"),
                                        null,
                                        20,
                                        null)
                                .items()
                                .getFirst()
                                .id())
                .isEqualTo(newest.id());
        assertThat(
                        knowledge
                                .browse(
                                        "member@example.com",
                                        workspaceFor("member@example.com"),
                                        null,
                                        500,
                                        null)
                                .items())
                .hasSize(26);
    }

    @Test
    void filtersFullCollectionByWritingTypeAndValidatesCursorContext() {
        for (int i = 0; i < 22; i++)
            knowledge.create("member@example.com", workspaceFor("member@example.com"), request());
        var decision = new TicketContent("Decision", null, "Decision", content().details());
        knowledge.create(
                "member@example.com",
                workspaceFor("member@example.com"),
                new CreateTicketRequest(UUID.randomUUID(), decision));
        jdbc.update("UPDATE KNOWLEDGE_TICKET SET CLASSIFICATION_STATUS='FAILED'");
        var coding =
                knowledge.browse(
                        "member@example.com",
                        workspaceFor("member@example.com"),
                        "Coding",
                        20,
                        null);
        assertThat(coding.items())
                .hasSize(20)
                .allSatisfy(
                        t -> {
                            assertThat(t.detailsType()).isEqualTo("Coding");
                            assertThat(t.classificationStatus()).isEqualTo("FAILED");
                        });
        assertThat(
                        knowledge
                                .browse(
                                        "member@example.com",
                                        workspaceFor("member@example.com"),
                                        "Coding",
                                        20,
                                        coding.nextCursor())
                                .items())
                .hasSize(2);
        assertThat(
                        knowledge
                                .browse(
                                        "member@example.com",
                                        workspaceFor("member@example.com"),
                                        "Decision",
                                        20,
                                        null)
                                .items())
                .hasSize(1);
        assertThat(
                        knowledge
                                .browse(
                                        "member@example.com",
                                        workspaceFor("member@example.com"),
                                        "Learning",
                                        20,
                                        null)
                                .items())
                .isEmpty();
        assertThatThrownBy(
                        () ->
                                knowledge.browse(
                                        "member@example.com",
                                        workspaceFor("member@example.com"),
                                        "Decision",
                                        20,
                                        coding.nextCursor()))
                .isInstanceOf(KnowledgeException.class);
        assertThatThrownBy(
                        () ->
                                knowledge.browse(
                                        "member@example.com",
                                        workspaceFor("member@example.com"),
                                        null,
                                        20,
                                        coding.nextCursor()))
                .isInstanceOf(KnowledgeException.class);
        for (String invalid : List.of("", "unknown", "coding"))
            assertThatThrownBy(
                            () ->
                                    knowledge.browse(
                                            "member@example.com",
                                            workspaceFor("member@example.com"),
                                            invalid,
                                            20,
                                            null))
                    .isInstanceOf(KnowledgeException.class);
        for (String invalid : List.of("", "invalid", "e30", "x".repeat(1025)))
            assertThatThrownBy(
                            () ->
                                    knowledge.browse(
                                            "member@example.com",
                                            workspaceFor("member@example.com"),
                                            null,
                                            20,
                                            invalid))
                    .isInstanceOf(KnowledgeException.class);
        assertThatThrownBy(
                        () ->
                                knowledge.browse(
                                        "member@example.com",
                                        workspaceFor("member@example.com"),
                                        null,
                                        0,
                                        null))
                .isInstanceOf(KnowledgeException.class);
    }

    @Test
    void collectionTransportIsLightweightBoundedAndEnforcesMembership() throws Exception {
        knowledge.create("member@example.com", workspaceFor("member@example.com"), request());
        mvc.perform(get("/api/knowledge/tickets")).andExpect(status().isUnauthorized());
        mvc.perform(
                        get("/api/knowledge/tickets")
                                .with(user("member@example.com").roles("MEMBER"))
                                .header("X-Workspace-Id", workspaceFor("member@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].title").value("Retry safely"))
                .andExpect(jsonPath("$.data.items[0].detailsType").value("Coding"))
                .andExpect(jsonPath("$.data.items[0].content").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].details").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].leaseToken").doesNotExist());
        mvc.perform(
                        get("/api/knowledge/tickets")
                                .with(user("outsider@example.com").roles("MEMBER"))
                                .header("X-Workspace-Id", workspaceFor("outsider@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(
                        get("/api/knowledge/tickets")
                                .param("detailsType", "wrong")
                                .with(user("member@example.com").roles("MEMBER"))
                                .header("X-Workspace-Id", workspaceFor("member@example.com")))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/api/knowledge/tickets")
                                .param("limit", "no")
                                .with(user("member@example.com").roles("MEMBER"))
                                .header("X-Workspace-Id", workspaceFor("member@example.com")))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE ACCOUNT SET STATUS='PENDING' WHERE ID=?", author);
        mvc.perform(
                        get("/api/knowledge/tickets")
                                .with(user("member@example.com").roles("MEMBER"))
                                .header("X-Workspace-Id", workspaceFor("member@example.com")))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE ACCOUNT SET STATUS='ACTIVE' WHERE ID=?", author);
        jdbc.update("UPDATE WORKSPACE SET STATUS='PENDING' WHERE ID=?", workspace);
        mvc.perform(
                        get("/api/knowledge/tickets")
                                .with(user("member@example.com").roles("MEMBER"))
                                .header("X-Workspace-Id", workspaceFor("member@example.com")))
                .andExpect(status().isForbidden());
    }

    @Test
    void concurrentRetriesPublishOnlyOnceAndChangedPayloadIsRejected() throws Exception {
        var request = request();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one =
                    pool.submit(
                            () ->
                                    knowledge.create(
                                            "member@example.com",
                                            workspaceFor("member@example.com"),
                                            request));
            var two =
                    pool.submit(
                            () ->
                                    knowledge.create(
                                            "member@example.com",
                                            workspaceFor("member@example.com"),
                                            request));
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
                                        workspaceFor("member@example.com"),
                                        new CreateTicketRequest(request.submissionId(), different)))
                .isInstanceOfSatisfying(
                        KnowledgeException.class, e -> assertThat(e.status()).isEqualTo(409));
    }

    @Test
    void classificationIsSeparateAndPersistsOnlyValidatedCatalogSelections() {
        var ticket =
                knowledge.create(
                        "member@example.com", workspaceFor("member@example.com"), request());
        // Make the job due independently of host/container clock precision.
        jdbc.update(
                "UPDATE KNOWLEDGE_TICKET SET NEXT_ATTEMPT_AT=CURRENT_TIMESTAMP-INTERVAL '1 second'"
                        + " WHERE ID=?",
                ticket.id());
        verifyNoInteractions(ai);
        when(ai.classify(content()))
                .thenReturn(List.of("area:backend", "type:coding", "area:backend"));
        worker.runOnce();
        var classified =
                knowledge.get(
                        "member@example.com", workspaceFor("member@example.com"), ticket.id());
        assertThat(classified.classificationStatus()).isEqualTo("COMPLETE");
        assertThat(classified.attributes()).containsExactly("area:backend", "type:coding");
    }

    @Test
    void invalidOutputAndProviderFailureRetryThreeTimesWithoutUnpublishing() {
        var ticket =
                knowledge.create(
                        "member@example.com", workspaceFor("member@example.com"), request());
        // Make the job due independently of host/container clock precision.
        jdbc.update(
                "UPDATE KNOWLEDGE_TICKET SET NEXT_ATTEMPT_AT=CURRENT_TIMESTAMP-INTERVAL '1 second'"
                        + " WHERE ID=?",
                ticket.id());
        when(ai.classify(content())).thenReturn(List.of("invented:attribute"));
        worker.runOnce();
        assertThat(
                        knowledge
                                .get(
                                        "member@example.com",
                                        workspaceFor("member@example.com"),
                                        ticket.id())
                                .classificationStatus())
                .isEqualTo("RETRYING");
        when(ai.classify(content())).thenThrow(new IllegalStateException("provider unavailable"));
        for (int i = 0; i < 2; i++) {
            jdbc.update(
                    "UPDATE KNOWLEDGE_TICKET SET NEXT_ATTEMPT_AT=CURRENT_TIMESTAMP-INTERVAL '1"
                            + " second'");
            worker.runOnce();
        }
        var published =
                knowledge.get(
                        "member@example.com", workspaceFor("member@example.com"), ticket.id());
        assertThat(published.classificationStatus()).isEqualTo("FAILED");
        assertThat(published.content()).isEqualTo(content());
        assertThat(published.attributes()).isEmpty();
        worker.runOnce();
        verify(ai, times(3)).classify(content());
    }

    @Test
    void leaseRecoveryFencesStaleWorkersAndEmptyResultsComplete() {
        var ticket =
                knowledge.create(
                        "member@example.com", workspaceFor("member@example.com"), request());
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
        assertThat(
                        knowledge
                                .get(
                                        "member@example.com",
                                        workspaceFor("member@example.com"),
                                        ticket.id())
                                .classificationStatus())
                .isEqualTo("PROCESSING");
        transactions.complete(newWork, List.of());
        assertThat(
                        knowledge
                                .get(
                                        "member@example.com",
                                        workspaceFor("member@example.com"),
                                        ticket.id())
                                .classificationStatus())
                .isEqualTo("COMPLETE");
        assertThat(
                        knowledge
                                .get(
                                        "member@example.com",
                                        workspaceFor("member@example.com"),
                                        ticket.id())
                                .attributes())
                .isEmpty();
    }

    @Test
    void refinementRephrasesOnlyExistingSelectedTextAndNeverMutatesPublication() {
        when(ai.refine(content().title(), "title")).thenReturn("Safe retries");
        var result =
                knowledge.refine(
                        "member@example.com",
                        workspaceFor("member@example.com"),
                        new RefinementRequest("title", content()));
        assertThat(result).isEqualTo(new RefinementView("title", "Safe retries"));
        verify(ai).refine(content().title(), "title");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM KNOWLEDGE_TICKET", Integer.class))
                .isZero();
        var withoutCapture =
                new TicketContent(content().title(), "", "Coding", content().details());
        assertThatThrownBy(
                        () ->
                                knowledge.refine(
                                        "member@example.com",
                                        workspaceFor("member@example.com"),
                                        new RefinementRequest("capture", withoutCapture)))
                .isInstanceOfSatisfying(
                        KnowledgeException.class, e -> assertThat(e.status()).isEqualTo(400));
        verify(ai, never()).refine(eq(""), anyString());
        when(ai.refine(content().title(), "title")).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(
                        () ->
                                knowledge.refine(
                                        "member@example.com",
                                        workspaceFor("member@example.com"),
                                        new RefinementRequest("title", content())))
                .isInstanceOfSatisfying(
                        KnowledgeException.class,
                        e -> assertThat(e.code()).isEqualTo("REFINEMENT_UNAVAILABLE"));
        assertThatThrownBy(
                        () ->
                                knowledge.refine(
                                        "member@example.com",
                                        workspaceFor("member@example.com"),
                                        new RefinementRequest("details", content())))
                .isInstanceOfSatisfying(
                        KnowledgeException.class, e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    void rejectsRefinementThatExpandsFarBeyondTheOriginalWording() {
        when(ai.refine(content().capture(), "capture"))
                .thenReturn(
                        "A long new explanation with facts and suggestions that the user never entered. "
                                .repeat(4));
        assertThatThrownBy(
                        () ->
                                knowledge.refine(
                                        "member@example.com",
                                        workspaceFor("member@example.com"),
                                        new RefinementRequest("capture", content())))
                .isInstanceOfSatisfying(
                        KnowledgeException.class,
                        e -> assertThat(e.code()).isEqualTo("REFINEMENT_UNAVAILABLE"));
        assertThat(content().capture()).isEqualTo("Reuse the same idempotency key.");
    }

    @Test
    void publishesWithOmittedCapture() {
        var withoutCapture =
                new TicketContent(content().title(), null, "Coding", content().details());
        var created =
                knowledge.create(
                        "member@example.com",
                        workspaceFor("member@example.com"),
                        new CreateTicketRequest(UUID.randomUUID(), withoutCapture));
        assertThat(
                        knowledge
                                .get(
                                        "member@example.com",
                                        workspaceFor("member@example.com"),
                                        created.id())
                                .content()
                                .capture())
                .isNull();
    }

    @Test
    void refinementTransportReturnsOnlySelectedFieldAndRejectsDetails() throws Exception {
        when(ai.refine(content().capture(), "capture")).thenReturn("Reuse a stable key.");
        mvc.perform(
                        post("/api/knowledge/tickets/refine")
                                .with(user("member@example.com").roles("MEMBER"))
                                .header("X-Workspace-Id", workspaceFor("member@example.com"))
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
                                .header("X-Workspace-Id", workspaceFor("member@example.com"))
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
                                .header("X-Workspace-Id", workspaceFor("member@example.com"))
                                .with(csrf())
                                .contentType("application/json")
                                .content(json.writeValueAsString(request())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.classificationStatus").value("PENDING"));
        mvc.perform(
                        post("/api/knowledge/tickets")
                                .with(user("member@example.com").roles("MEMBER"))
                                .header("X-Workspace-Id", workspaceFor("member@example.com"))
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
        mvc.perform(
                        get("/actuator/metrics")
                                .with(user("member@example.com").roles("MEMBER"))
                                .header("X-Workspace-Id", workspaceFor("member@example.com")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/actuator/metrics/jvm.memory.used")
                                .with(user("admin@example.com").roles("ADMIN"))
                                .header("X-Workspace-Id", workspaceFor("admin@example.com")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/actuator/prometheus")
                                .with(user("admin@example.com").roles("ADMIN"))
                                .header("X-Workspace-Id", workspaceFor("admin@example.com")))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE ACCOUNT SET STATUS='PENDING' WHERE EMAIL='admin@example.com'");
        mvc.perform(
                        get("/actuator/metrics")
                                .with(user("admin@example.com").roles("ADMIN"))
                                .header("X-Workspace-Id", workspaceFor("admin@example.com")))
                .andExpect(status().isForbidden());
    }

    UUID workspaceFor(String email) {
        return jdbc.queryForObject(
                "SELECT DEFAULT_WORKSPACE_ID FROM ACCOUNT WHERE EMAIL = ?", UUID.class, email);
    }
}
