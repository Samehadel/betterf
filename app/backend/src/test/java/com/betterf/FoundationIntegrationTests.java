package com.betterf;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FoundationIntegrationTests {
    @Container
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:17.6-alpine");
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    final HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();

    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(java.time.Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void startupAppliesMigrationAndServesTheStatusContract() throws Exception {
        var response = get("/api/status");
        assertThat(response.statusCode()).isEqualTo(200);
        var body = mapper.readTree(response.body());
        assertThat(body.path("data").path("status").asText()).isEqualTo("UP");
        assertThat(body.path("error").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG WHERE ID = '20260922-001-baseline'", Integer.class)).isEqualTo(1);
    }

    @Test
    void probesStayUnwrappedAndUnknownPathsAreDenied() throws Exception {
        for (String probe : new String[] {"liveness", "readiness"}) {
            var response = get("/actuator/health/" + probe);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(response.body()).path("status").asText()).isEqualTo("UP");
            assertThat(mapper.readTree(response.body()).has("data")).isFalse();
        }
        var denied = get("/api/private");
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(mapper.readTree(denied.body()).path("error").path("code").asText()).isEqualTo("ACCESS_DENIED");
        var post = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/status")).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(403);
        assertThat(mapper.readTree(post.body()).path("error").path("code").asText()).isEqualTo("ACCESS_DENIED");
    }
    @Test
    void databaseOutageFailsReadinessButNotLivenessAndRecovers() throws Exception {
        database.getDockerClient().pauseContainerCmd(database.getContainerId()).exec();
        try {
            var status = get("/api/status");
            assertThat(status.statusCode()).isEqualTo(503);
            assertThat(mapper.readTree(status.body()).path("error").path("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
            assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(503);
            assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        } finally {
            database.getDockerClient().unpauseContainerCmd(database.getContainerId()).exec();
        }
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15))
            .untilAsserted(() -> assertThat(get("/api/status").statusCode()).isEqualTo(200));
    }

}
