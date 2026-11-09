package ca.vm.parcelflow.security;

import static org.assertj.core.api.Assertions.assertThat;

import ca.vm.parcelflow.support.PostgresIntegrationTest;
import ca.vm.parcelflow.support.security.TestBrowser;
import ca.vm.parcelflow.support.security.TestBrowser.Response;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The OIDC login, end to end, against a real Keycloak running the committed realm
 * ({@code keycloak/realm/parcelflow-realm.json}) and the real application on a real port.
 *
 * <p>Nothing is stubbed: the test drives Keycloak's own login form, the authorization code comes
 * back through Spring Security's callback, the code is exchanged with the client secret and PKCE
 * verifier, and the ID token is validated against Keycloak's keys. Two retailer accounts, an admin,
 * and a retailer with no assignment exercise authorization on top.
 *
 * <p>Credentials are CI-only constants passed to the container's realm import; nothing here is a
 * secret anywhere else.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class KeycloakOidcLoginIntegrationTest extends PostgresIntegrationTest {

    private static final String CLIENT_SECRET = "ci-only-client-secret";
    private static final String ADMIN_PASSWORD = "ci-only-admin-password";
    private static final String RETAILER_PASSWORD = "ci-only-retailer-password";
    private static final String KEYCLOAK_ADMIN_PASSWORD = "ci-only-keycloak-admin";

    private static final String SESSION_COOKIE = "PARCELFLOW_SESSION";
    /** The cookie name a double-submit CSRF design would use; this application must never set it. */
    private static final String XSRF_COOKIE = "XSRF-TOKEN";

    @SuppressWarnings("resource")
    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.8.1")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", KEYCLOAK_ADMIN_PASSWORD)
            .withEnv("PARCELFLOW_OIDC_CLIENT_SECRET", CLIENT_SECRET)
            .withEnv("PARCELFLOW_ADMIN_PASSWORD", ADMIN_PASSWORD)
            .withEnv("PARCELFLOW_RETAILER_PASSWORD", RETAILER_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "keycloak", "realm", "parcelflow-realm.json")),
                    "/opt/keycloak/data/import/parcelflow-realm.json")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/parcelflow/.well-known/openid-configuration")
                    .forPort(8080)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    static {
        KEYCLOAK.start();
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static boolean clientRegistered;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void oidc(DynamicPropertyRegistry registry) {
        registry.add("parcelflow.security.oidc.issuer-uri", KeycloakOidcLoginIntegrationTest::issuer);
        registry.add("parcelflow.security.oidc.client-secret", () -> CLIENT_SECRET);
    }

    private static String keycloakBase() {
        return "http://localhost:" + KEYCLOAK.getMappedPort(8080);
    }

    /** No KC_HOSTNAME: Keycloak derives the issuer from the request, and both sides use this URL. */
    private static String issuer() {
        return keycloakBase() + "/realms/parcelflow";
    }

    private String app() {
        return "http://localhost:" + port;
    }

    /**
     * The committed realm registers the developer ports (8081, 4200, 8080). This test's port is random,
     * so its callback and post-logout URIs are added through Keycloak's admin API — exact URIs, as a
     * deployment would register them, not a wildcard.
     */
    @BeforeEach
    void registerThisPortWithKeycloak() throws Exception {
        if (clientRegistered) {
            return;
        }
        HttpClient http = HttpClient.newHttpClient();
        String token = JSON.readTree(http.send(HttpRequest.newBuilder(
                        URI.create(keycloakBase() + "/realms/master/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=password&client_id=admin-cli&username=admin"
                        + "&password=" + KEYCLOAK_ADMIN_PASSWORD))
                .build(), HttpResponse.BodyHandlers.ofString()).body()).get("access_token").asString();

        String clients = keycloakBase() + "/admin/realms/parcelflow/clients";
        JsonNode found = JSON.readTree(http.send(
                HttpRequest.newBuilder(URI.create(clients + "?clientId=parcelflow-bff"))
                        .header("Authorization", "Bearer " + token).build(),
                HttpResponse.BodyHandlers.ofString()).body());
        ObjectNode client = (ObjectNode) found.get(0);
        ((ArrayNode) client.get("redirectUris")).add(app() + "/login/oauth2/code/keycloak");
        ObjectNode attributes = (ObjectNode) client.get("attributes");
        attributes.put("post.logout.redirect.uris",
                attributes.get("post.logout.redirect.uris").asString() + "##" + app() + "/signed-out");

        HttpResponse<String> update = http.send(HttpRequest.newBuilder(
                        URI.create(clients + "/" + client.get("id").asString()))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(client)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(update.statusCode()).isEqualTo(204);
        clientRegistered = true;
    }

    /** Logs in and returns the browser, asserting each hop of the protocol on the way. */
    private TestBrowser login(String username, String password) throws Exception {
        TestBrowser browser = new TestBrowser();
        Response callback = authenticateAtKeycloak(browser, username, password, "");
        Response landing = browser.get(callback.location());
        assertThat(landing.status()).as("callback response").isEqualTo(302);
        assertThat(landing.location()).isEqualTo(app() + "/");
        return browser;
    }

    /** Runs the flow up to (not including) the callback; returns Keycloak's redirect to it. */
    private Response authenticateAtKeycloak(TestBrowser browser, String username, String password, String query)
            throws Exception {
        Response start = browser.get(app() + "/oauth2/authorization/keycloak" + query);
        assertThat(start.status()).isEqualTo(302);
        Response loginPage = browser.get(start.location());
        assertThat(loginPage.status()).as("Keycloak login page").isEqualTo(200);
        Response afterLogin = browser.submitLogin(loginPage, username, password);
        assertThat(afterLogin.status()).as("Keycloak after credentials").isEqualTo(302);
        assertThat(afterLogin.location()).startsWith(app() + "/login/oauth2/code/keycloak?");
        return afterLogin;
    }

    private static Map<String, String> query(String url) {
        String raw = URI.create(url).getRawQuery();
        return Arrays.stream(raw.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(
                        kv -> kv[0],
                        kv -> kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "",
                        (a, b) -> b, LinkedHashMap::new));
    }

    /** The session's CSRF token, fetched the way the UI fetches it. */
    private JsonNode csrfToken(TestBrowser browser) throws Exception {
        Response response = browser.get(app() + "/api/auth/csrf");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.setCookie(XSRF_COOKIE)).as("no CSRF cookie").isNull();
        JsonNode token = JSON.readTree(response.body());
        assertThat(token.get("headerName").asString()).isEqualTo("X-XSRF-TOKEN");
        return token;
    }

    private Map<String, String> xsrf(TestBrowser browser) throws Exception {
        return Map.of("X-XSRF-TOKEN", csrfToken(browser).get("token").asString());
    }

    private Response register(TestBrowser browser, String retailerId, String trackingNumber) throws Exception {
        return browser.postJson(app() + "/api/shipments", """
                {"retailerId":"%s","customerId":"c-1","trackingNumber":"%s","carrierCode":"SWIFTPOST"}"""
                .formatted(retailerId, trackingNumber), xsrf(browser));
    }

    @Test
    @DisplayName("authorization code + PKCE S256 through the BFF callback yields an HttpOnly server-side session")
    void loginEstablishesServerSideSession() throws Exception {
        TestBrowser browser = new TestBrowser();

        Response start = browser.get(app() + "/oauth2/authorization/keycloak");
        assertThat(start.status()).isEqualTo(302);
        Map<String, String> authorization = query(start.location());
        assertThat(start.location()).startsWith(issuer() + "/protocol/openid-connect/auth?");
        assertThat(authorization)
                .containsEntry("response_type", "code")
                .containsEntry("client_id", "parcelflow-bff")
                .containsEntry("redirect_uri", app() + "/login/oauth2/code/keycloak")
                .containsEntry("code_challenge_method", "S256")
                .containsKeys("state", "nonce", "code_challenge");
        assertThat(authorization.get("scope").split(" ")).contains("openid");
        assertThat(authorization).doesNotContainKey("prompt");
        String preLoginSession = browser.cookie(app(), SESSION_COOKIE);
        assertThat(preLoginSession).as("session holding the authorization request").isNotBlank();

        Response loginPage = browser.get(start.location());
        Response callback = browser.submitLogin(loginPage, "retailer.alpha", RETAILER_PASSWORD);
        assertThat(query(callback.location())).containsKeys("code", "state").containsEntry("state",
                authorization.get("state"));

        Response landing = browser.get(callback.location());
        assertThat(landing.status()).isEqualTo(302);
        assertThat(landing.location()).isEqualTo(app() + "/");

        // Session fixation protection: the id that carried the authorization request is retired.
        String postLoginSession = browser.cookie(app(), SESSION_COOKIE);
        assertThat(postLoginSession).isNotBlank().isNotEqualTo(preLoginSession);
        String sessionCookie = landing.setCookie(SESSION_COOKIE);
        assertThat(sessionCookie).containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Lax")
                .contains("Path=/");
        // The CSRF token is in the session, never in a cookie a sibling origin could overwrite.
        assertThat(landing.setCookie(XSRF_COOKIE)).isNull();
        assertThat(browser.cookie(app(), XSRF_COOKIE)).isNull();

        Response session = browser.get(app() + "/api/auth/session");
        assertThat(session.status()).isEqualTo(200);
        JsonNode body = JSON.readTree(session.body());
        assertThat(body.get("authenticated").asBoolean()).isTrue();
        assertThat(body.at("/user/role").asString()).isEqualTo("RETAILER");
        assertThat(body.at("/user/retailerId").asString()).isEqualTo("alpha-goods");
        assertThat(body.at("/user/username").asString()).isEqualTo("retailer.alpha");
        assertThat(body.at("/user/subject").asString()).isEqualTo("0d2b6c9e-7a14-4f3b-8e25-1c9f4a6d2b02");
        // No token of any kind reaches the browser.
        assertThat(session.body()).doesNotContain("eyJ");
        assertThat(landing.body() + session.body()).doesNotContainIgnoringCase("access_token")
                .doesNotContainIgnoringCase("refresh_token").doesNotContainIgnoringCase("id_token");
    }

    @Test
    @DisplayName("two retailers and an admin, from real logins: isolation holds end to end")
    void twoRetailersAreIsolated() throws Exception {
        TestBrowser alpha = login("retailer.alpha", RETAILER_PASSWORD);
        TestBrowser beta = login("retailer.beta", RETAILER_PASSWORD);
        TestBrowser admin = login("ops.admin", ADMIN_PASSWORD);

        String trackingNumber = "SP-OIDC-" + UUID.randomUUID().toString().substring(0, 8);
        Response created = register(alpha, "alpha-goods", trackingNumber);
        assertThat(created.status()).isEqualTo(201);
        String shipmentId = JSON.readTree(created.body()).get("shipmentId").asString();

        // Beta: the shipment does not exist, the list is forbidden, the forged body is refused.
        assertThat(beta.get(app() + "/api/shipments/" + shipmentId).status()).isEqualTo(404);
        assertThat(beta.get(app() + "/api/shipments/" + shipmentId + "/events").status()).isEqualTo(404);
        assertThat(beta.get(app() + "/api/shipments/" + shipmentId + "/notifications").status()).isEqualTo(404);
        String lookup = app() + "/api/shipments/lookup?carrierCode=SWIFTPOST&trackingNumber=";
        assertThat(beta.get(lookup + trackingNumber).status()).isEqualTo(404);
        assertThat(beta.get(app() + "/api/retailers/alpha-goods/shipments").status()).isEqualTo(403);
        assertThat(register(beta, "alpha-goods", "SP-OIDC-FORGED").status()).isEqualTo(403);
        Response duplicate = register(beta, "beta-supplies", trackingNumber);
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(JSON.readTree(duplicate.body()).get("existingShipmentAccessible").asBoolean()).isFalse();
        assertThat(duplicate.body()).doesNotContain(shipmentId).doesNotContain("alpha-goods");
        assertThat(beta.get(app() + "/api/admin/failed-events").status()).isEqualTo(403);

        // Alpha and the admin see it.
        assertThat(alpha.get(app() + "/api/shipments/" + shipmentId).status()).isEqualTo(200);
        assertThat(admin.get(app() + "/api/shipments/" + shipmentId).status()).isEqualTo(200);
        assertThat(admin.get(app() + "/api/retailers/alpha-goods/shipments").status()).isEqualTo(200);
        assertThat(admin.get(app() + "/api/admin/failed-events").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("CSRF: only the session's own token is accepted; a planted XSRF-TOKEN cookie is worthless")
    void csrfTokenIsSessionBound() throws Exception {
        TestBrowser alpha = login("retailer.alpha", RETAILER_PASSWORD);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {"retailerId":"alpha-goods","customerId":"c",
                 "trackingNumber":"SP-XSRF-%s-%s","carrierCode":"SWIFTPOST"}""";

        Response withoutHeader = alpha.postJson(app() + "/api/shipments", body.formatted(suffix, 1), Map.of());
        assertThat(withoutHeader.status()).isEqualTo(403);
        assertThat(JSON.readTree(withoutHeader.body()).get("type").asString()).endsWith("/csrf-token-invalid");

        Response wrongHeader = alpha.postJson(app() + "/api/shipments", body.formatted(suffix, 2),
                Map.of("X-XSRF-TOKEN", "not-the-token"));
        assertThat(wrongHeader.status()).isEqualTo(403);

        // Cookie tossing: another origin on this host plants a token it knows and submits it. With a
        // double-submit cookie that matched; with a session-bound token it does not.
        alpha.setCookie(app(), XSRF_COOKIE, "planted-by-another-origin");
        Response tossed = alpha.postJson(app() + "/api/shipments", body.formatted(suffix, 3),
                Map.of("X-XSRF-TOKEN", "planted-by-another-origin"));
        assertThat(tossed.status()).isEqualTo(403);
        assertThat(JSON.readTree(tossed.body()).get("type").asString()).endsWith("/csrf-token-invalid");

        // Another user's valid token is not this session's token either.
        TestBrowser beta = login("retailer.beta", RETAILER_PASSWORD);
        Response foreign = alpha.postJson(app() + "/api/shipments", body.formatted(suffix, 4), xsrf(beta));
        assertThat(foreign.status()).isEqualTo(403);

        // The session's own token, from GET /api/auth/csrf (planted cookie still present: ignored).
        Response withHeader = alpha.postJson(app() + "/api/shipments", body.formatted(suffix, 5), xsrf(alpha));
        assertThat(withHeader.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("spoofed X-Forwarded-Host / Forwarded headers cannot change the OIDC redirect origins")
    void spoofedForwardedHostIsIgnored() throws Exception {
        Map<String, String> spoofed = Map.of(
                "X-Forwarded-Host", "evil.example",
                "Forwarded", "host=evil.example;proto=https");

        Response start = new TestBrowser().get(app() + "/oauth2/authorization/keycloak", spoofed);
        assertThat(start.status()).isEqualTo(302);
        assertThat(query(start.location())).containsEntry("redirect_uri", app() + "/login/oauth2/code/keycloak");

        // The post-logout redirect is built the same way.
        TestBrowser browser = login("retailer.alpha", RETAILER_PASSWORD);
        JsonNode token = csrfToken(browser);
        Response logout = browser.postForm(app() + "/logout",
                Map.of(token.get("parameterName").asString(), token.get("token").asString()), spoofed);
        assertThat(logout.status()).isEqualTo(302);
        assertThat(query(logout.location())).containsEntry("post_logout_redirect_uri", app() + "/signed-out");
    }

    @Test
    @DisplayName("logout: local session invalidated, Keycloak SSO session ended (RP-initiated), safe return URL")
    void logoutEndsBothSessions() throws Exception {
        TestBrowser browser = login("retailer.beta", RETAILER_PASSWORD);
        String sessionBefore = browser.cookie(app(), SESSION_COOKIE);

        // The UI posts a form with the masked token from /api/auth/csrf (BREACH-safe encoding).
        JsonNode token = csrfToken(browser);
        Response logout = browser.postForm(app() + "/logout",
                Map.of(token.get("parameterName").asString(), token.get("token").asString()), Map.of());

        assertThat(logout.status()).isEqualTo(302);
        assertThat(logout.location()).startsWith(issuer() + "/protocol/openid-connect/logout?");
        Map<String, String> endSession = query(logout.location());
        assertThat(endSession).containsEntry("post_logout_redirect_uri", app() + "/signed-out")
                .containsKey("id_token_hint");
        // Tomcat expires a cookie with an epoch Expires attribute rather than Max-Age=0.
        assertThat(logout.setCookie(SESSION_COOKIE))
                .matches("(?i)PARCELFLOW_SESSION=;.*(max-age=0|expires=thu, 01 jan 1970).*");
        assertThat(logout.setCookie(XSRF_COOKIE)).isNull();

        Response keycloakLogout = browser.get(logout.location());
        assertThat(keycloakLogout.status()).isEqualTo(302);
        assertThat(keycloakLogout.location()).isEqualTo(app() + "/signed-out");

        // The old session id is dead even if replayed.
        TestBrowser replay = new TestBrowser();
        replay.setCookie(app(), SESSION_COOKIE, sessionBefore);
        assertThat(replay.get(app() + "/api/retailers/beta-supplies/shipments").status()).isEqualTo(401);
        assertThat(JSON.readTree(browser.get(app() + "/api/auth/session").body())
                .get("authenticated").asBoolean()).isFalse();
        // The token died with the session: replaying it, even with the old session id, is refused.
        Response staleToken = replay.postForm(app() + "/logout",
                Map.of(token.get("parameterName").asString(), token.get("token").asString()), Map.of());
        assertThat(staleToken.status()).isEqualTo(403);

        // The Keycloak SSO session is gone: signing in again shows the login form instead of
        // silently logging the same user back in.
        Response again = browser.get(browser.get(app() + "/oauth2/authorization/keycloak").location());
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.html().selectFirst("form#kc-form-login")).isNotNull();
    }

    @Test
    @DisplayName("prompt=login forces re-authentication even while the Keycloak SSO session is alive")
    void promptLoginForcesReauthentication() throws Exception {
        TestBrowser browser = login("retailer.alpha", RETAILER_PASSWORD);

        // Without prompt: SSO answers straight away with a code.
        Response silent = browser.get(browser.get(app() + "/oauth2/authorization/keycloak").location());
        assertThat(silent.status()).isEqualTo(302);
        assertThat(silent.location()).startsWith(app() + "/login/oauth2/code/keycloak?");

        // With prompt=login: the login form, despite the live SSO session.
        Response start = browser.get(app() + "/oauth2/authorization/keycloak?prompt=login");
        assertThat(query(start.location())).containsEntry("prompt", "login");
        Response form = browser.get(start.location());
        assertThat(form.status()).isEqualTo(200);
        assertThat(form.html().selectFirst("form#kc-form-login")).isNotNull();

        // Any other prompt value is not forwarded.
        Response other = browser.get(app() + "/oauth2/authorization/keycloak?prompt=none");
        assertThat(query(other.location())).doesNotContainKey("prompt");
    }

    @Test
    @DisplayName("a RETAILER without a retailer assignment is refused at login (fail closed); no session")
    void unassignedRetailerIsRefused() throws Exception {
        TestBrowser browser = new TestBrowser();
        Response callback = authenticateAtKeycloak(browser, "retailer.unassigned", RETAILER_PASSWORD, "");

        Response result = browser.get(callback.location());
        assertThat(result.status()).isEqualTo(302);
        assertThat(result.location()).isEqualTo(app() + "/login?error=not_authorized");
        assertThat(JSON.readTree(browser.get(app() + "/api/auth/session").body())
                .get("authenticated").asBoolean()).isFalse();
        assertThat(browser.get(app() + "/api/retailers/alpha-goods/shipments").status()).isEqualTo(401);
    }

    @Test
    @DisplayName("a tampered state and a replayed authorization code are both rejected")
    void tamperedStateAndReplayedCode() throws Exception {
        TestBrowser browser = new TestBrowser();
        Response callback = authenticateAtKeycloak(browser, "retailer.alpha", RETAILER_PASSWORD, "");
        String tampered = callback.location().replaceFirst("state=[^&]+", "state=forged-state");

        Response rejected = browser.get(tampered);
        assertThat(rejected.status()).isEqualTo(302);
        assertThat(rejected.location()).isEqualTo(app() + "/login?error=login_failed");
        assertThat(browser.get(app() + "/api/retailers/alpha-goods/shipments").status()).isEqualTo(401);

        // A second, genuine flow succeeds, and replaying its callback afterwards does not.
        TestBrowser second = new TestBrowser();
        Response genuine = authenticateAtKeycloak(second, "retailer.alpha", RETAILER_PASSWORD, "");
        assertThat(second.get(genuine.location()).location()).isEqualTo(app() + "/");
        Response replayed = second.get(genuine.location());
        assertThat(replayed.location()).isEqualTo(app() + "/login?error=login_failed");
    }

    @Test
    @DisplayName("an API call after the session is gone is 401 JSON with a correlation id, not a login redirect")
    void apiAfterSessionLoss() throws IOException, InterruptedException {
        TestBrowser browser = new TestBrowser();
        Response response = browser.get(app() + "/api/shipments/" + UUID.randomUUID(),
                Map.of("X-Correlation-Id", "oidc-it-1"));
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.headers().get("content-type").getFirst()).startsWith("application/problem+json");
        assertThat(response.headers().get("x-correlation-id")).containsExactly("oidc-it-1");
    }
}
