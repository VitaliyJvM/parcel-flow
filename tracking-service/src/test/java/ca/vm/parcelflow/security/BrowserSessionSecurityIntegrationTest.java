package ca.vm.parcelflow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.vm.parcelflow.support.KafkaIntegrationTest;
import ca.vm.parcelflow.support.security.TestIdentities;
import ca.vm.parcelflow.security.web.SessionLifetimeFilter;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The browser-session contract the Angular UI depends on: what an unauthenticated, expired or
 * forged request gets back, how CSRF is enforced, and what logout does — all without an identity
 * provider (the OIDC protocol itself is {@code KeycloakOidcLoginIntegrationTest}).
 *
 * <p>Tests that send {@code csrf()} use Spring Security Test's token; the CSRF tests that matter
 * for the real contract (a token from {@code GET /api/auth/csrf}, bound to the session, and a
 * planted cookie that is worthless) send no {@code csrf()} and go through the real repository.
 * Token rotation at login and logout is asserted against a real server in
 * {@code KeycloakOidcLoginIntegrationTest}.
 *
 * <p>Extends {@link KafkaIntegrationTest} because the operational tests call {@code /actuator/health}
 * and readiness, and readiness includes the Kafka contributor. Without a broker container the
 * context falls back to the production default bootstrap ({@code localhost:19092}, the Docker
 * Compose Redpanda), so the result would depend on whether a developer's local stack happens to be
 * running.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BrowserSessionSecurityIntegrationTest extends KafkaIntegrationTest {

    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;
    private static final String PROBLEMS = "https://parcelflow.example/problems/";
    private static final String REGISTER_BODY = """
            {"retailerId":"alpha-goods","customerId":"c","trackingNumber":"SP-CSRF-%s","carrierCode":"SWIFTPOST"}""";

    @Autowired
    private MockMvc mockMvc;

    @Nested
    @DisplayName("unauthenticated and expired sessions")
    class Unauthenticated {

        @Test
        @DisplayName("an API call without a session is 401 problem+json, never a redirect to a login page")
        void apiWithoutSessionIs401() throws Exception {
            mockMvc.perform(get("/api/retailers/alpha-goods/shipments")
                            .header("X-Correlation-Id", "anon-1")
                            .header("X-Requested-With", "XMLHttpRequest"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value(PROBLEMS + "authentication-required"))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(header().string("X-Correlation-Id", "anon-1"))
                    .andExpect(header().doesNotExist("Location"));
        }

        @Test
        @DisplayName("every protected API family answers 401 the same way")
        void everyFamilyIs401() throws Exception {
            for (String path : List.of("/api/shipments/00000000-0000-0000-0000-000000000000",
                    "/api/shipments/00000000-0000-0000-0000-000000000000/events",
                    "/api/shipments/00000000-0000-0000-0000-000000000000/notifications",
                    "/api/shipments/lookup?carrierCode=SWIFTPOST&trackingNumber=X",
                    "/api/admin/failed-events")) {
                mockMvc.perform(get(path))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.type").value(PROBLEMS + "authentication-required"));
            }
        }

        @Test
        @DisplayName("an expired or unknown session cookie is treated as no session")
        void expiredSessionIs401() throws Exception {
            mockMvc.perform(get("/api/retailers/alpha-goods/shipments")
                            .cookie(new Cookie("PARCELFLOW_SESSION", "8F14E45FCEEA167A5A36DEDD4BEA2543")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value(PROBLEMS + "authentication-required"));
        }

        @Test
        @DisplayName("a session whose identity cannot be authorized is refused (fail closed)")
        void unauthorizableIdentityIsRefused() throws Exception {
            // A principal that somehow carries the RETAILER authority but no retailer claim. The
            // URL rule passes it; the identity mapper, re-run per request, does not.
            mockMvc.perform(get("/api/retailers/alpha-goods/shipments")
                            .with(TestIdentities.withClaims(Map.of("sub", "s-1", "roles", List.of("RETAILER")),
                                    ParcelFlowRole.RETAILER.authority())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value(PROBLEMS + "access-denied"));
        }

        @Test
        @DisplayName("an ordinary browser navigation to a protected backend URL goes to the UI login route")
        void browserNavigationRedirectsToLogin() throws Exception {
            mockMvc.perform(get("/").accept(MediaType.TEXT_HTML))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("/login"));
        }

        @Test
        @DisplayName("starting a login while the identity provider is unreachable fails closed to the login page")
        void loginWithoutIdentityProvider() throws Exception {
            mockMvc.perform(get("/oauth2/authorization/keycloak"))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("/login?error=idp_unavailable"));
        }

        @Test
        @DisplayName("there is no HTTP Basic, form login or bearer-token fallback")
        void noFallbackAuthentication() throws Exception {
            mockMvc.perform(get("/api/retailers/alpha-goods/shipments")
                            .header("Authorization", "Basic b3BzLmFkbWluOnBhc3N3b3Jk"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/retailers/alpha-goods/shipments")
                            .header("Authorization", "Bearer eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4In0."))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(post("/login").with(csrf()).param("username", "ops.admin").param("password", "x"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("session endpoint")
    class SessionEndpoint {

        @Test
        @DisplayName("anonymous: 200 with authenticated=false")
        void anonymousSession() throws Exception {
            mockMvc.perform(get("/api/auth/session"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authenticated").value(false))
                    .andExpect(jsonPath("$.user").doesNotExist())
                    .andExpect(header().string("Cache-Control", "no-store"));
        }

        @Test
        @DisplayName("a retailer: identity, role and the server-assigned retailer — and no token of any kind")
        void retailerSession() throws Exception {
            String body = mockMvc.perform(get("/api/auth/session").with(TestIdentities.retailer("alpha-goods")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authenticated").value(true))
                    .andExpect(jsonPath("$.user.role").value("RETAILER"))
                    .andExpect(jsonPath("$.user.retailerId").value("alpha-goods"))
                    .andExpect(jsonPath("$.user.subject").value("sub-alpha-goods"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body.toLowerCase()).doesNotContain("token").doesNotContain("secret");
        }

        @Test
        @DisplayName("an admin: no retailer")
        void adminSession() throws Exception {
            mockMvc.perform(get("/api/auth/session").with(TestIdentities.admin()))
                    .andExpect(jsonPath("$.user.role").value("ADMIN"))
                    .andExpect(jsonPath("$.user.retailerId").doesNotExist());
        }
    }

    @Nested
    @DisplayName("CSRF")
    class Csrf {

        @Test
        @DisplayName("a state-changing request without a token is refused with a distinct problem type")
        void postWithoutTokenIsRefused() throws Exception {
            mockMvc.perform(post("/api/shipments").with(TestIdentities.retailer("alpha-goods"))
                            .contentType(MediaType.APPLICATION_JSON).content(REGISTER_BODY.formatted("1"))
                            .header("X-Correlation-Id", "csrf-1"))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value(PROBLEMS + "csrf-token-invalid"))
                    .andExpect(header().string("X-Correlation-Id", "csrf-1"));
        }

        @Test
        @DisplayName("a forged token is refused")
        void forgedTokenIsRefused() throws Exception {
            mockMvc.perform(post("/api/shipments").with(TestIdentities.retailer("alpha-goods"))
                            .with(csrf().useInvalidToken())
                            .contentType(MediaType.APPLICATION_JSON).content(REGISTER_BODY.formatted("2")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value(PROBLEMS + "csrf-token-invalid"));
        }

        @Test
        @DisplayName("the token is never a cookie: neither the session nor the CSRF endpoint sets one")
        void tokenIsNotACookie() throws Exception {
            MockHttpSession session = new MockHttpSession();
            for (String path : List.of("/api/auth/session", "/api/auth/csrf")) {
                mockMvc.perform(get(path).session(session).with(TestIdentities.retailer("alpha-goods")))
                        .andExpect(status().isOk())
                        .andExpect(cookie().doesNotExist("XSRF-TOKEN"))
                        .andExpect(header().doesNotExist("Set-Cookie"));
            }
        }

        @Test
        @DisplayName("a token from GET /api/auth/csrf is accepted in X-XSRF-TOKEN, by its own session only")
        void sessionTokenInHeader() throws Exception {
            MockHttpSession session = new MockHttpSession();
            Map<String, String> csrf = csrfHeader(session);
            String token = csrf.get(headerName(csrf));

            mockMvc.perform(post("/api/shipments").session(session).with(TestIdentities.retailer("alpha-goods"))
                            .header(headerName(csrf), token)
                            .contentType(MediaType.APPLICATION_JSON).content(REGISTER_BODY.formatted("3")))
                    .andExpect(status().isCreated());

            // Another session's token is not this session's token.
            MockHttpSession other = new MockHttpSession();
            csrfHeader(other);
            mockMvc.perform(post("/api/shipments").session(other).with(TestIdentities.retailer("alpha-goods"))
                            .header(headerName(csrf), token)
                            .contentType(MediaType.APPLICATION_JSON).content(REGISTER_BODY.formatted("4")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value(PROBLEMS + "csrf-token-invalid"));
        }

        @Test
        @DisplayName("cookie tossing: a planted XSRF-TOKEN cookie with a matching header or form field is refused")
        void plantedCookieIsWorthless() throws Exception {
            // An origin that can set cookies for this host (a sibling subdomain; on localhost, any
            // other local service) plants a token it knows. With a double-submit cookie this was a
            // valid token; with the session-bound token it is just a wrong one.
            MockHttpSession session = new MockHttpSession();
            String header = headerName(csrfHeader(session));
            String planted = "attacker-chosen-token";
            Cookie tossed = new Cookie("XSRF-TOKEN", planted);

            mockMvc.perform(post("/api/shipments").session(session).with(TestIdentities.retailer("alpha-goods"))
                            .cookie(tossed).header(header, planted).header("X-XSRF-TOKEN", planted)
                            .contentType(MediaType.APPLICATION_JSON).content(REGISTER_BODY.formatted("5")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value(PROBLEMS + "csrf-token-invalid"));

            // The form-POST variant an attacker page can actually send cross-site: the body-less
            // admin retry and logout, with the planted token XOR-masked as Spring encodes it.
            mockMvc.perform(post("/api/admin/failed-events/" + UUID.randomUUID() + "/retry")
                            .session(session).with(TestIdentities.admin())
                            .cookie(tossed).param("_csrf", xorMasked(planted))
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value(PROBLEMS + "csrf-token-invalid"));
            mockMvc.perform(post("/logout").session(session).with(TestIdentities.admin())
                            .cookie(tossed).param("_csrf", xorMasked(planted)))
                    .andExpect(status().isForbidden());
            assertThat(session.isInvalid()).as("not logged out").isFalse();
        }

        @Test
        @DisplayName("logout without a token is refused, so a cross-site page cannot log a user out")
        void logoutNeedsToken() throws Exception {
            mockMvc.perform(post("/logout").with(TestIdentities.admin()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /logout does not log anyone out")
        void getLogoutDoesNothing() throws Exception {
            mockMvc.perform(get("/logout").with(TestIdentities.admin()))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(302));
        }
    }

    @Nested
    @DisplayName("session lifetime: 30 min without user activity, 8 h absolute")
    class SessionLifetime {

        private static final String LIST = "/api/retailers/alpha-goods/shipments";

        /** An authenticated session as a real login leaves it, with backdated timestamps. */
        private MockHttpSession session(Duration sinceLogin, Duration sinceActivity) {
            MockHttpSession session = new MockHttpSession();
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                    new SecurityContextImpl(TestIdentities.retailerAuthentication("alpha-goods")));
            Instant now = Instant.now();
            session.setAttribute(SessionLifetimeFilter.AUTHENTICATED_AT, now.minus(sinceLogin));
            session.setAttribute(SessionLifetimeFilter.LAST_ACTIVITY_AT, now.minus(sinceActivity));
            return session;
        }

        private void assertEnded(MockHttpSession session) throws Exception {
            mockMvc.perform(get(LIST).session(session))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value(PROBLEMS + "authentication-required"));
            assertThat(session.isInvalid()).as("session invalidated").isTrue();
        }

        @Test
        @DisplayName("a session within both limits works, and a user request counts as activity")
        void liveSession() throws Exception {
            MockHttpSession session = session(Duration.ofHours(7).plusMinutes(50), Duration.ofMinutes(29));
            Instant before = (Instant) session.getAttribute(SessionLifetimeFilter.LAST_ACTIVITY_AT);
            mockMvc.perform(get(LIST).session(session)).andExpect(status().isOk());
            assertThat((Instant) session.getAttribute(SessionLifetimeFilter.LAST_ACTIVITY_AT)).isAfter(before);
        }

        @Test
        @DisplayName("absolute: 8 h after login the session ends, however recent the activity: 401")
        void absoluteLifetime() throws Exception {
            assertEnded(session(Duration.ofHours(8).plusSeconds(1), Duration.ofSeconds(5)));
        }

        @Test
        @DisplayName("idle: 30 min without user activity ends the session: 401")
        void idleTimeout() throws Exception {
            assertEnded(session(Duration.ofHours(1), Duration.ofMinutes(30).plusSeconds(1)));
        }

        @Test
        @DisplayName("background polling is served but is not activity; a user request is")
        void backgroundIsNotActivity() throws Exception {
            MockHttpSession session = session(Duration.ofHours(1), Duration.ofMinutes(20));
            Object lastActivity = session.getAttribute(SessionLifetimeFilter.LAST_ACTIVITY_AT);

            mockMvc.perform(get(LIST).session(session).header(SessionLifetimeFilter.BACKGROUND_HEADER, "true"))
                    .andExpect(status().isOk());
            assertThat(session.getAttribute(SessionLifetimeFilter.LAST_ACTIVITY_AT)).isEqualTo(lastActivity);

            mockMvc.perform(get(LIST).session(session)).andExpect(status().isOk());
            assertThat(session.getAttribute(SessionLifetimeFilter.LAST_ACTIVITY_AT)).isNotEqualTo(lastActivity);
        }

        @Test
        @DisplayName("after expiry the session endpoint reports signed out")
        void sessionEndpointAfterExpiry() throws Exception {
            mockMvc.perform(get("/api/auth/session").session(session(Duration.ofHours(9), Duration.ofSeconds(1))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authenticated").value(false));
        }

        @Test
        @DisplayName("a write on an expired session is 401 (sign in again), not a CSRF 403")
        void writeAfterExpiryIs401() throws Exception {
            MockHttpSession session = session(Duration.ofHours(8).plusMinutes(1), Duration.ofMinutes(1));
            mockMvc.perform(post("/api/shipments").session(session).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(REGISTER_BODY.formatted("expired")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value(PROBLEMS + "authentication-required"));
        }

        @Test
        @DisplayName("a session with no lifetime record gets one on first sight: never unbounded")
        void missingLifetimeRecord() throws Exception {
            MockHttpSession session = session(Duration.ofMinutes(1), Duration.ofMinutes(1));
            session.removeAttribute(SessionLifetimeFilter.AUTHENTICATED_AT);
            session.removeAttribute(SessionLifetimeFilter.LAST_ACTIVITY_AT);
            Instant before = Instant.now();
            mockMvc.perform(get(LIST).session(session)).andExpect(status().isOk());
            assertThat((Instant) session.getAttribute(SessionLifetimeFilter.AUTHENTICATED_AT)).isAfterOrEqualTo(before);
        }

        @Test
        @DisplayName("a session missing only its login time is ended, not restarted: 401")
        void missingLoginTimeFailsClosed() throws Exception {
            MockHttpSession session = session(Duration.ofHours(9), Duration.ofMinutes(1));
            session.removeAttribute(SessionLifetimeFilter.AUTHENTICATED_AT);
            assertEnded(session);
        }
    }

    /**
     * The session's (masked) CSRF token and the header to send it in, the way the UI gets them.
     *
     * <p>The header name is taken from the response rather than asserted: once any test in this
     * shared context has used Spring Security Test's {@code csrf()}, the filter's repository is that
     * post-processor's wrapper around a default {@code HttpSessionCsrfTokenRepository}, which names
     * the header {@code X-CSRF-TOKEN}. The production name ({@code X-XSRF-TOKEN}) is asserted against
     * a real server in {@code KeycloakOidcLoginIntegrationTest}.
     */
    private Map<String, String> csrfHeader(MockHttpSession session) throws Exception {
        String body = mockMvc.perform(get("/api/auth/csrf").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Map.of(JsonPath.read(body, "$.headerName"), JsonPath.read(body, "$.token"));
    }

    private static String headerName(Map<String, String> csrfHeader) {
        return csrfHeader.keySet().iterator().next();
    }

    /** Spring Security's BREACH-safe encoding: random bytes, then the token XOR those bytes. Public. */
    private static String xorMasked(String token) {
        byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        byte[] random = new byte[tokenBytes.length];
        new SecureRandom().nextBytes(random);
        byte[] combined = new byte[tokenBytes.length * 2];
        System.arraycopy(random, 0, combined, 0, random.length);
        for (int i = 0; i < tokenBytes.length; i++) {
            combined[random.length + i] = (byte) (random[i] ^ tokenBytes[i]);
        }
        return Base64.getUrlEncoder().encodeToString(combined);
    }

    @Nested
    @DisplayName("logout")
    class Logout {

        @Test
        @DisplayName("invalidates the session (and with it the CSRF token) and clears the session cookie")
        void logoutInvalidatesSession() throws Exception {
            MockHttpSession session = new MockHttpSession();
            mockMvc.perform(get("/api/auth/session").session(session).with(TestIdentities.retailer("alpha-goods")))
                    .andExpect(jsonPath("$.authenticated").value(true));

            mockMvc.perform(post("/logout").session(session).with(TestIdentities.retailer("alpha-goods")).with(csrf()))
                    .andExpect(status().isFound())
                    // No OIDC provider in this test, so the local fallback; with Keycloak it is the
                    // end-session endpoint (KeycloakOidcLoginIntegrationTest).
                    .andExpect(redirectedUrl("/signed-out"))
                    .andExpect(cookie().maxAge("PARCELFLOW_SESSION", 0));

            assertThat(session.isInvalid()).isTrue();
        }
    }

    @Nested
    @DisplayName("operational endpoints")
    class Operational {

        @Test
        @DisplayName("health is public but shows component details only to an ADMIN")
        void healthDetailsNeedAdmin() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").exists())
                    .andExpect(jsonPath("$.components").doesNotExist());
            mockMvc.perform(get("/actuator/health").with(TestIdentities.retailer("alpha-goods")))
                    .andExpect(jsonPath("$.components").doesNotExist());
            mockMvc.perform(get("/actuator/health").with(TestIdentities.admin()))
                    .andExpect(jsonPath("$.components.db.status").value("UP"));
        }

        @Test
        @DisplayName("the readiness probe and the Prometheus scrape need no session")
        void probesArePublic() throws Exception {
            mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
            mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("any other actuator path needs ADMIN and is answered as an API error")
        void otherActuatorNeedsAdmin() throws Exception {
            mockMvc.perform(get("/actuator/metrics"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value(PROBLEMS + "authentication-required"));
            mockMvc.perform(get("/actuator/metrics").with(TestIdentities.retailer("alpha-goods")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("the OpenAPI document stays public and declares the session-cookie scheme")
        void openApiIsPublic() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.components.securitySchemes.browserSession.in").value("cookie"))
                    .andExpect(jsonPath("$.paths./api/auth/session.get").exists());
        }

        @Test
        @DisplayName("responses carry the standard security headers")
        void securityHeaders() throws Exception {
            mockMvc.perform(get("/api/auth/session"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("X-Frame-Options", "DENY"));
        }
    }
}
