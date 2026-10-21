package com.betterf.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.betterf.identity.api.service.IdentityService;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes the real authorization-code filters, token exchange, JWT checks, and session handler.
 */
@Testcontainers
@SpringBootTest(
        properties = {
            "betterf.registration.delivery.enabled=false",
            "betterf.knowledge.classification.enabled=false"
        })
@AutoConfigureMockMvc
class SocialCallbackIntegrationTests {
    @Container
    static PostgreSQLContainer database = new PostgreSQLContainer("postgres:17.6-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdentityService identity;
    @MockitoBean ClientRegistrationRepository registrations;
    static HttpServer server;
    static RSAKey key;
    static String origin;
    static final AtomicReference<String> tokenBody = new AtomicReference<>();
    static final AtomicReference<String> profileBody = new AtomicReference<>();
    static int tokenStatus;
    static int profileStatus;

    @BeforeAll
    static void startProvider() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/token",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] body = tokenBody.get().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(tokenStatus, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/userinfo",
                exchange -> {
                    byte[] body = profileBody.get().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(profileStatus, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/jwks",
                exchange -> {
                    byte[] body =
                            new JWKSet(key.toPublicJWK())
                                    .toString()
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopProvider() {
        server.stop(0);
    }

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE ACCOUNT, WORKSPACE, REFRESH_SESSION CASCADE");
        tokenStatus = 200;
        profileStatus = 200;
        tokenBody.set(
                "{\"access_token\":\"test-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
        profileBody.set(
                "{\"id\":\"facebook-subject\",\"name\":\"Personal"
                        + " User\",\"email\":\"person@example.com\"}");
        when(registrations.findByRegistrationId("facebook")).thenReturn(registration("facebook"));
        when(registrations.findByRegistrationId("linkedin")).thenReturn(registration("linkedin"));
    }

    ClientRegistration registration(String provider) {
        var builder =
                ClientRegistration.withRegistrationId(provider)
                        .clientId("test-client")
                        .clientSecret("test-secret")
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                        .redirectUri("http://localhost/api/auth/oauth/callback/" + provider)
                        .authorizationUri(origin + "/authorize")
                        .tokenUri(origin + "/token")
                        .userInfoUri(origin + "/userinfo")
                        .userNameAttributeName("id");
        if (provider.equals("linkedin")) {
            builder.scope("openid", "profile", "email")
                    .jwkSetUri(origin + "/jwks")
                    .issuerUri(origin)
                    .userNameAttributeName("sub");
        } else builder.scope("email");
        return builder.build();
    }

    record Authorization(MockHttpSession session, String state, String nonce) {}

    Authorization begin(String provider) throws Exception {
        var result =
                mvc.perform(get("/api/auth/oauth/authorize/" + provider))
                        .andExpect(status().is3xxRedirection())
                        .andReturn();
        var query =
                UriComponentsBuilder.fromUriString(result.getResponse().getRedirectedUrl())
                        .build()
                        .getQueryParams();
        return new Authorization(
                (MockHttpSession) result.getRequest().getSession(),
                java.net.URLDecoder.decode(query.getFirst("state"), StandardCharsets.UTF_8),
                query.getFirst("nonce"));
    }

    org.springframework.test.web.servlet.ResultActions callback(
            String provider, Authorization authorization) throws Exception {
        return mvc.perform(
                get("/api/auth/oauth/callback/" + provider)
                        .session(authorization.session())
                        .param("code", "test-code")
                        .param("state", authorization.state()));
    }

    void oidcToken(
            Authorization authorization,
            Boolean verified,
            String email,
            String nonce,
            RSAKey signingKey)
            throws Exception {
        var claims =
                new JWTClaimsSet.Builder()
                        .issuer(origin)
                        .subject("linkedin-subject")
                        .audience("test-client")
                        .issueTime(Date.from(Instant.now()))
                        .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                        .claim("nonce", nonce == null ? authorization.nonce() : nonce)
                        .claim("name", "Personal User");
        if (email != null) claims.claim("email", email);
        if (verified != null) claims.claim("email_verified", verified);
        var token =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),
                        claims.build());
        token.sign(new RSASSASigner(signingKey));
        tokenBody.set(
                JsonMapper.shared()
                        .writeValueAsString(
                                Map.of(
                                        "access_token",
                                        "test-token",
                                        "token_type",
                                        "Bearer",
                                        "expires_in",
                                        3600,
                                        "id_token",
                                        token.serialize())));
    }

    void noIdentity() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACCOUNT", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM OAUTH_IDENTITY", Long.class)).isZero();
    }

    @Test
    void facebookMissingVerificationCreatesPersonalSessionAndRejectsRepeatedCallback()
            throws Exception {
        var authorization = begin("facebook");
        callback("facebook", authorization)
                .andExpect(redirectedUrl("/login?social=SIGNUP_REQUIRED"));
        mvc.perform(get("/api/auth/me").session(authorization.session()))
                .andExpect(status().isUnauthorized());
        callback("facebook", authorization)
                .andExpect(redirectedUrl("/login?social=PROVIDER_AUTHENTICATION_FAILED"));
        noIdentity();
    }

    @Test
    void invalidStateAndProviderDenialCannotCreateAccountsOrSessions() throws Exception {
        var authorization = begin("facebook");
        mvc.perform(
                        get("/api/auth/oauth/callback/facebook")
                                .session(authorization.session())
                                .param("state", "wrong")
                                .param("code", "test"))
                .andExpect(redirectedUrl("/login?social=PROVIDER_AUTHENTICATION_FAILED"));
        noIdentity();
        var denied = begin("facebook");
        mvc.perform(
                        get("/api/auth/oauth/callback/facebook")
                                .session(denied.session())
                                .param("state", denied.state())
                                .param("error", "access_denied"))
                .andExpect(redirectedUrl("/login?social=PROVIDER_AUTHENTICATION_FAILED"));
        mvc.perform(get("/api/auth/me").session(denied.session()))
                .andExpect(status().isUnauthorized());
        noIdentity();
    }

    @Test
    void tokenFailureAndMissingEmailNeverUseVerificationFallback() throws Exception {
        tokenStatus = 401;
        tokenBody.set("{\"error\":\"invalid_grant\"}");
        callback("facebook", begin("facebook"))
                .andExpect(redirectedUrl("/login?social=PROVIDER_AUTHENTICATION_FAILED"));
        noIdentity();
        tokenStatus = 200;
        tokenBody.set("{\"access_token\":\"test-token\",\"token_type\":\"Bearer\"}");
        profileBody.set("{\"id\":\"facebook-subject\",\"name\":\"Personal\"}");
        var missing = begin("facebook");
        callback("facebook", missing)
                .andExpect(redirectedUrl("/login?social=PROVIDER_EMAIL_MISSING"));
        mvc.perform(get("/api/auth/me").session(missing.session()))
                .andExpect(status().isUnauthorized());
        noIdentity();
    }

    @Test
    void validatedLinkedinTokenSurvivesOptionalProfileOutageButExplicitFalseFails()
            throws Exception {
        var authorization = begin("linkedin");
        oidcToken(authorization, null, "person@example.com", null, key);
        profileStatus = 503;
        profileBody.set("{}");
        callback("linkedin", authorization)
                .andExpect(redirectedUrl("/login?social=SIGNUP_REQUIRED"));
        noIdentity();
        var rejected = begin("linkedin");
        oidcToken(rejected, false, "other@example.com", null, key);
        callback("linkedin", rejected)
                .andExpect(redirectedUrl("/login?social=PROVIDER_EMAIL_UNVERIFIED"));
        assertThat(identity.credentials("other@example.com")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM OAUTH_IDENTITY", Long.class)).isZero();
    }

    @Test
    void oidcRejectsInvalidSignatureNonceAndSubjectMismatch() throws Exception {
        profileBody.set(
                "{\"sub\":\"linkedin-subject\",\"email\":\"person@example.com\",\"email_verified\":true}");
        var signature = begin("linkedin");
        oidcToken(
                signature,
                true,
                "person@example.com",
                null,
                new RSAKeyGenerator(2048).keyID("test-key").generate());
        callback("linkedin", signature)
                .andExpect(redirectedUrl("/login?social=PROVIDER_AUTHENTICATION_FAILED"));
        var nonce = begin("linkedin");
        oidcToken(nonce, true, "person@example.com", "wrong-nonce", key);
        callback("linkedin", nonce)
                .andExpect(redirectedUrl("/login?social=PROVIDER_AUTHENTICATION_FAILED"));
        var mismatch = begin("linkedin");
        oidcToken(mismatch, true, "person@example.com", null, key);
        profileBody.set("{\"sub\":\"different-subject\",\"email\":\"person@example.com\"}");
        callback("linkedin", mismatch)
                .andExpect(redirectedUrl("/login?social=PROVIDER_AUTHENTICATION_FAILED"));
        noIdentity();
    }

    @Test
    void existingEmailCreatesPendingLinkWithoutGrantingAccess() throws Exception {
        identity.register(
                new com.betterf.identity.api.dto.RegistrationRequest(
                        "Person", "person@example.com", "Validpass!"));
        var authorization = begin("facebook");
        callback("facebook", authorization).andExpect(redirectedUrl("/login?social=LINK_REQUIRED"));
        mvc.perform(get("/api/auth/me").session(authorization.session()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/social/pending").session(authorization.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("facebook"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACCOUNT", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM OAUTH_IDENTITY", Long.class)).isZero();
    }
}
