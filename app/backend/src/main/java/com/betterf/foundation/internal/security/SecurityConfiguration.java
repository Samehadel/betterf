package com.betterf.foundation.internal.security;

import com.betterf.foundation.internal.http.ApiEnvelope;
import com.betterf.identity.api.service.IdentityService;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;

@Configuration
@EnableScheduling
class SecurityConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder passwords() {
        return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    SecurityContextRepository contexts() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    CsrfTokenRepository csrfTokens() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    SessionAuthenticationStrategy sessionStrategy(CsrfTokenRepository csrf) {
        return new CompositeSessionAuthenticationStrategy(
                List.of(
                        new ChangeSessionIdAuthenticationStrategy(),
                        new CsrfAuthenticationStrategy(csrf)));
    }

    @Bean
    AuthenticationManager authentication(IdentityService identity, PasswordEncoder passwords) {
        var provider =
                new DaoAuthenticationProvider(
                        email -> {
                            var account = identity.credentials(email);
                            if (account == null)
                                throw new UsernameNotFoundException("Invalid credentials");
                            return User.withUsername(account.email())
                                    .password(account.passwordHash())
                                    .disabled(!account.active())
                                    .authorities(
                                            "ROLE_"
                                                    + (account.accessRole() == null
                                                            ? "PENDING"
                                                            : account.accessRole()))
                                    .build();
                        });
        provider.setPasswordEncoder(passwords);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityFilterChain security(
            HttpSecurity http,
            JsonMapper mapper,
            SecurityContextRepository contexts,
            CsrfTokenRepository csrf)
            throws Exception {
        return http.sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(context -> context.securityContextRepository(contexts))
                .csrf(config -> config.csrfTokenRepository(csrf))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                HttpMethod.GET,
                                                "/api/status",
                                                "/actuator/health/liveness",
                                                "/actuator/health/readiness",
                                                "/api/auth/csrf",
                                                "/api/registration/roles")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/registration",
                                                "/api/registration/resend",
                                                "/api/registration/status",
                                                "/api/registration/verify",
                                                "/api/auth/login")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/api/auth/me")
                                        .authenticated()
                                        .requestMatchers(HttpMethod.POST, "/api/invitations", "/api/invitations/status")
                                        .hasRole("ADMIN")
                                        .anyRequest()
                                        .denyAll())
                .logout(
                        logout ->
                                logout.logoutUrl("/api/auth/logout")
                                        .deleteCookies("JSESSIONID")
                                        .logoutSuccessHandler(
                                                (request, response, auth) ->
                                                        response.setStatus(204)))
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                (request, response, exception) ->
                                                        forbidden(response, mapper))
                                        .accessDeniedHandler(
                                                (request, response, exception) ->
                                                        forbidden(response, mapper)))
                .build();
    }

    private void forbidden(HttpServletResponse response, JsonMapper mapper)
            throws java.io.IOException {
        response.setStatus(403);
        response.setContentType("application/json");
        mapper.writeValue(
                response.getOutputStream(),
                ApiEnvelope.failure(
                        "ACCESS_DENIED",
                        "Access is denied. Refresh the page and log in if needed."));
    }
}
