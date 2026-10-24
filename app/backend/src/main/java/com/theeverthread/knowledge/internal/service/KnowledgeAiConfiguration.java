package com.theeverthread.knowledge.internal.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

@Configuration
class KnowledgeAiConfiguration {
    @Bean
    KnowledgeAi knowledgeAi(
            @Lazy ChatClient knowledgeChatClient,
            JsonMapper json,
            @Value("${spring.ai.openai.api-key}") String apiKey) {
        requireApiKey(apiKey);
        return new SpringAiKnowledgeAi(knowledgeChatClient, json);
    }

    @Bean
    @Lazy
    OpenAiChatModel knowledgeChatModel(
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.chat.model}") String model,
            @Value("${spring.ai.openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${spring.ai.openai.timeout}") Duration timeout,
            @Value("${spring.ai.openai.chat.max-completion-tokens}") int maxCompletionTokens,
            @Value("${spring.ai.openai.chat.reasoning-effort}") String reasoningEffort,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {
        var options =
                OpenAiChatOptions.builder()
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .model(model)
                        .store(false)
                        .reasoningEffort(reasoningEffort)
                        .maxCompletionTokens(maxCompletionTokens)
                        .timeout(timeout)
                        .maxRetries(0)
                        .build();
        return OpenAiChatModel.builder()
                .options(options)
                .observationRegistry(observationRegistry)
                .meterRegistry(meterRegistry)
                .build();
    }

    @Bean
    @Lazy
    ChatClient knowledgeChatClient(
            @Lazy ChatModel knowledgeChatModel, ObservationRegistry observationRegistry) {
        return ChatClient.builder(knowledgeChatModel, observationRegistry, null, null)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .defaultAdvisors(advisors -> advisors.param("provider", "openai"))
                .build();
    }

    private void requireApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank() || "unconfigured".equals(apiKey)) {
            throw new IllegalStateException("OpenAI is not configured");
        }
    }
}
