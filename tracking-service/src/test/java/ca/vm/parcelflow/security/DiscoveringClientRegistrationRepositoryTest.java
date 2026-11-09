package ca.vm.parcelflow.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * Discovery against a stub provider. The real-provider path is covered by
 * {@code KeycloakOidcLoginIntegrationTest}; these are the refusals that a real Keycloak cannot be
 * made to produce.
 */
class DiscoveringClientRegistrationRepositoryTest {

    private static final String PUBLIC_ISSUER = "http://localhost:8180/realms/parcelflow";

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private URI serve(String issuer, String pkceMethods) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String internal = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/parcelflow";
        String body = """
                {"issuer":"%s",
                 "authorization_endpoint":"%s/protocol/openid-connect/auth",
                 "token_endpoint":"%s/protocol/openid-connect/token",
                 "jwks_uri":"%s/protocol/openid-connect/certs",
                 "userinfo_endpoint":"%s/protocol/openid-connect/userinfo",
                 "end_session_endpoint":"%s/protocol/openid-connect/logout",
                 "response_types_supported":["code"],
                 "subject_types_supported":["public"],
                 "id_token_signing_alg_values_supported":["RS256"],
                 "code_challenge_methods_supported":%s}
                """.formatted(issuer, PUBLIC_ISSUER, internal, internal, internal, PUBLIC_ISSUER, pkceMethods);
        server.createContext("/realms/parcelflow/.well-known/openid-configuration", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return URI.create(internal + "/.well-known/openid-configuration");
    }

    private static DiscoveringClientRegistrationRepository repository(URI discovery) {
        return new DiscoveringClientRegistrationRepository(new SecurityProperties.Oidc(
                "keycloak", URI.create(PUBLIC_ISSUER), discovery, "parcelflow-bff", "s3cret",
                List.of("openid", "profile", "email"), "/signed-out", Duration.ofSeconds(2)));
    }

    @Test
    @DisplayName("internal discovery URI, public issuer: a confidential client with PKCE required")
    void discoversWithSplitNetworkPaths() throws IOException {
        DiscoveringClientRegistrationRepository repository = repository(serve(PUBLIC_ISSUER, "[\"S256\"]"));

        ClientRegistration registration = repository.findByRegistrationId("keycloak");

        assertThat(registration).isNotNull();
        assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo(PUBLIC_ISSUER);
        assertThat(registration.getProviderDetails().getAuthorizationUri()).startsWith(PUBLIC_ISSUER);
        assertThat(registration.getProviderDetails().getTokenUri()).startsWith("http://127.0.0.1:");
        assertThat(registration.getClientAuthenticationMethod())
                .isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(registration.getAuthorizationGrantType()).isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
        assertThat(registration.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(registration.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
        assertThat(registration.getProviderDetails().getConfigurationMetadata()).containsKey("end_session_endpoint");
    }

    @Test
    @DisplayName("the registration is cached after the first successful discovery")
    void cachesTheRegistration() throws IOException {
        DiscoveringClientRegistrationRepository repository = repository(serve(PUBLIC_ISSUER, "[\"S256\"]"));

        repository.findByRegistrationId("keycloak");
        repository.findByRegistrationId("keycloak");

        assertThat(requests).hasValue(1);
    }

    @Test
    @DisplayName("a discovery document advertising another issuer is refused")
    void refusesIssuerMismatch() throws IOException {
        DiscoveringClientRegistrationRepository repository =
                repository(serve("http://keycloak:8080/realms/parcelflow", "[\"S256\"]"));

        assertThat(repository.findByRegistrationId("keycloak")).isNull();
        assertThat(repository.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("a provider without PKCE S256 is refused")
    void refusesProviderWithoutS256() throws IOException {
        assertThat(repository(serve(PUBLIC_ISSUER, "[\"plain\"]")).findByRegistrationId("keycloak")).isNull();
    }

    @Test
    @DisplayName("an unreachable provider means no registration, and a later attempt retries")
    void unreachableProvider() {
        DiscoveringClientRegistrationRepository repository =
                repository(URI.create("http://127.0.0.1:1/realms/parcelflow/.well-known/openid-configuration"));

        assertThat(repository.isAvailable()).isFalse();
        assertThat(repository.cachedRegistration()).isNull();
    }

    @Test
    @DisplayName("an unknown registration id and a missing client secret yield nothing")
    void unknownIdAndUnconfiguredClient() throws IOException {
        URI discovery = serve(PUBLIC_ISSUER, "[\"S256\"]");
        assertThat(repository(discovery).findByRegistrationId("github")).isNull();

        DiscoveringClientRegistrationRepository noSecret = new DiscoveringClientRegistrationRepository(
                new SecurityProperties.Oidc("keycloak", URI.create(PUBLIC_ISSUER), discovery, "parcelflow-bff", "",
                        List.of("openid"), "/signed-out", Duration.ofSeconds(2)));
        assertThat(noSecret.findByRegistrationId("keycloak")).isNull();
        assertThat(requests).hasValue(0);
    }
}
