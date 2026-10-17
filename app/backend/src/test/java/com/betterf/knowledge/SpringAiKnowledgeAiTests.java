package com.betterf.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.betterf.knowledge.api.dto.TicketContent;
import com.betterf.knowledge.internal.service.SpringAiKnowledgeAi;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

class SpringAiKnowledgeAiTests {
    @Test
    void loadsSeparateTemplatesAndSendsOnlySelectedWordingForRefinement() throws Exception {
        var json = JsonMapper.shared();
        var requests = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    requests.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    var answer =
                            switch (requests.size()) {
                                case 1 -> Map.of("text", "Retry safely");
                                case 2 -> Map.of("text", "Use a stable key.");
                                default -> Map.of("attributes", List.of("area:backend"));
                            };
                    var body =
                            json.writeValueAsBytes(
                                    Map.of(
                                            "id",
                                            "chatcmpl-test",
                                            "object",
                                            "chat.completion",
                                            "created",
                                            1,
                                            "model",
                                            "gpt-5.4-nano",
                                            "choices",
                                            List.of(
                                                    Map.of(
                                                            "index",
                                                            0,
                                                            "finish_reason",
                                                            "stop",
                                                            "message",
                                                            Map.of(
                                                                    "role",
                                                                    "assistant",
                                                                    "content",
                                                                    json.writeValueAsString(
                                                                            answer))))));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            var model =
                    OpenAiChatModel.builder()
                            .options(
                                    OpenAiChatOptions.builder()
                                            .apiKey("local-test-key")
                                            .baseUrl(
                                                    "http://127.0.0.1:"
                                                            + server.getAddress().getPort())
                                            .model("gpt-5.4-nano")
                                            .temperature(null)
                                            .maxRetries(0)
                                            .timeout(Duration.ofSeconds(5))
                                            .store(false)
                                            .build())
                            .build();
            var ai = new SpringAiKnowledgeAi(ChatClient.create(model), json, "local-test-key");
            var content =
                    new TicketContent(
                            "retry safely",
                            "Use a stable key",
                            "Coding",
                            List.of(
                                    new TicketContent.DetailBlock(
                                            "code", "return key;", "java", null)));
            assertThat(ai.refine(content.title(), "title")).isEqualTo("Retry safely");
            assertThat(ai.refine(content.capture(), "capture")).isEqualTo("Use a stable key.");
            assertThat(ai.classify(content)).containsExactly("area:backend");
            for (var request : requests) {
                var payload = json.readTree(request);
                assertThat(payload.path("model").asText()).isEqualTo("gpt-5.4-nano");
                assertThat(
                                payload.path("temperature").isMissingNode()
                                        || payload.path("temperature").isNull())
                        .isTrue();
                assertThat(payload.path("response_format").path("type").asText())
                        .isEqualTo("json_schema");
                assertThat(payload.path("store").asBoolean()).isFalse();
                assertThat(request)
                        .doesNotContain(
                                "organizationId",
                                "authorId",
                                "authorName",
                                "submissionId",
                                "createdAt");
            }
            for (int i = 0; i < 2; i++) {
                var payload = json.readTree(requests.get(i));
                var messages = payload.path("messages");
                var system = messages.get(0).path("content").asText();
                var user = json.readTree(messages.get(1).path("content").asText());
                String original = i == 0 ? content.title() : content.capture();
                assertThat(user.size()).isEqualTo(1);
                assertThat(user.path("text").asText()).isEqualTo(original);
                assertThat(requests.get(i)).doesNotContain("return key;", "Coding", "details");
                assertThat(system)
                        .contains(
                                i == 0
                                        ? "existing knowledge-ticket Title"
                                        : "existing knowledge-ticket Capture");
                assertThat(system).contains("original has " + original.length() + " characters");
                assertThat(system).doesNotContain("{inputLength}", "{maxCharacters}");
            }
            assertThat(requests.get(0)).doesNotContain("Use a stable key");
            assertThat(requests.get(1)).doesNotContain("retry safely");
            assertThat(requests.get(2)).contains("return key;", "Use a stable key", "retry safely");
        } finally {
            server.stop(0);
        }
    }
}
