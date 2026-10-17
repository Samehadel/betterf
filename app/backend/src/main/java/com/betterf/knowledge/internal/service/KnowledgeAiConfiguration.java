package com.betterf.knowledge.internal.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class KnowledgeAiConfiguration {
    @Bean
    KnowledgeAi knowledgeAi(
            ChatClient knowledgeChatClient,
            tools.jackson.databind.json.JsonMapper json,
            @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.api-key}")
                    String apiKey) {
        return new SpringAiKnowledgeAi(knowledgeChatClient, json, apiKey);
    }

    @Bean
    ChatClient knowledgeChatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
