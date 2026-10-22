package com.betterf.knowledge.internal.service;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class KnowledgeAiConfigurationTests {
    private ApplicationContextRunner runner(MeterRegistry meters) {
        var observations = ObservationRegistry.create();
        observations
                .observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meters));
        return new ApplicationContextRunner()
                .withInitializer(
                        context ->
                                context.getBeanFactory()
                                        .setConversionService(
                                                ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(KnowledgeAiConfiguration.class)
                .withBean(ObservationRegistry.class, () -> observations)
                .withBean(MeterRegistry.class, () -> meters)
                .withBean(JsonMapper.class, JsonMapper::shared)
                .withPropertyValues(
                        "spring.ai.openai.api-key=local-test-key",
                        "spring.ai.openai.chat.model=gpt-5.4-nano",
                        "spring.ai.openai.timeout=45s",
                        "spring.ai.openai.chat.max-completion-tokens=2048",
                        "spring.ai.openai.chat.reasoning-effort=none");
    }

    @Test
    void createsClientAndModelLazilyWithConfiguredOptions() {
        runner(new SimpleMeterRegistry())
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(
                                            context.getBeanFactory()
                                                    .containsSingleton("knowledgeChatClient"))
                                    .isFalse();
                            assertThat(
                                            context.getBeanFactory()
                                                    .containsSingleton("knowledgeChatModel"))
                                    .isFalse();
                            var options = context.getBean(OpenAiChatModel.class).getOptions();
                            assertThat(options.getModel()).isEqualTo("gpt-5.4-nano");
                            assertThat(options.getReasoningEffort()).isEqualTo("none");
                            assertThat(options.getMaxCompletionTokens()).isEqualTo(2048);
                            assertThat(options.getTimeout()).isEqualTo(Duration.ofSeconds(45));
                            assertThat(options.getMaxRetries()).isZero();
                            assertThat(options.getStore()).isFalse();
                            assertThat(options.getTemperature()).isNull();
                        });
    }

    @Test
    void keepsExistingFailFastValidationForMissingKey() {
        runner(new SimpleMeterRegistry())
                .withPropertyValues("spring.ai.openai.api-key=unconfigured")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasRootCauseMessage("OpenAI is not configured");
                        });
    }

    @Test
    void sendsConfiguredOptionsAndRecordsModelAndClientMetrics() throws Exception {
        var json = JsonMapper.shared();
        var request = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    request.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    var answer = json.writeValueAsString(Map.of("text", "Retry safely"));
                    var body =
                            json.writeValueAsBytes(
                                    Map.of(
                                            "id",
                                            "chatcmpl-local",
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
                                                                    answer)))));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        var meters = new SimpleMeterRegistry();
        try {
            runner(meters)
                    .withPropertyValues(
                            "spring.ai.openai.base-url=http://127.0.0.1:"
                                    + server.getAddress().getPort())
                    .run(
                            context -> {
                                assertThat(
                                                context.getBean(KnowledgeAi.class)
                                                        .refine("retry safely", "title"))
                                        .isEqualTo("Retry safely");
                                var payload = json.readTree(request.get());
                                assertThat(payload.path("reasoning_effort").asText())
                                        .isEqualTo("none");
                                assertThat(payload.path("max_completion_tokens").asInt())
                                        .isEqualTo(2048);
                                assertThat(payload.path("store").asBoolean()).isFalse();
                                assertThat(meters.getMeters())
                                        .extracting(meter -> meter.getId().getName())
                                        .contains(
                                                "spring.ai.chat.client", "gen_ai.client.operation");
                            });
        } finally {
            server.stop(0);
            meters.close();
        }
    }
}
