package com.betterf.foundation.internal.security;

import com.betterf.foundation.internal.http.ApiEnvelope;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

@Configuration
class SecurityConfiguration {
    @Bean
    SecurityFilterChain security(HttpSecurity http, JsonMapper mapper) throws Exception {
        return http
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/status", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> forbidden(response, mapper))
                .accessDeniedHandler((request, response, exception) -> forbidden(response, mapper)))
            .build();
    }

    private void forbidden(HttpServletResponse response, JsonMapper mapper) throws java.io.IOException {
        response.setStatus(403);
        response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), ApiEnvelope.failure("ACCESS_DENIED", "Access is denied."));
    }
}
