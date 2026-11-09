package ca.vm.parcelflow.security;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistration.ClientSettings;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.web.client.RestClient;

/**
 * The Keycloak client registration, built from standard OIDC discovery on first use.
 *
 * <p><b>Why not {@code spring.security.oauth2.client.provider.*.issuer-uri}.</b> Boot's property
 * fetches {@code <issuer>/.well-known/openid-configuration} at startup. Inside Docker Compose the
 * browser reaches Keycloak at {@code http://localhost:8180} while this container can only reach it
 * at {@code http://keycloak:8080}, and an issuer is a single string. The tempting fixes all weaken
 * OIDC: dropping {@code issuer-uri} and hard-coding endpoints disables Spring's issuer check;
 * rewriting the issuer to the internal name makes every browser redirect point at a hostname the
 * browser cannot resolve.
 *
 * <p>Instead Keycloak is told its public URL ({@code KC_HOSTNAME}) and to answer back-channel
 * requests on whatever host they arrive at ({@code KC_HOSTNAME_BACKCHANNEL_DYNAMIC}). Fetched over
 * the internal network, its discovery document then advertises the <em>public</em> issuer and
 * authorization endpoint, and <em>internal</em> token, userinfo and JWKS endpoints. This class
 * fetches that document from {@link SecurityProperties.Oidc#effectiveDiscoveryUri()} and refuses it
 * unless its {@code issuer} equals the configured public issuer exactly. Spring Security then
 * validates every ID token's {@code iss} against that same value. Outside Docker the two URIs are
 * the same and this is ordinary discovery.
 *
 * <p><b>Why lazily.</b> The service starts, serves health checks and processes Kafka events while
 * the identity provider is still starting or briefly down. Until discovery succeeds there is no
 * registration, so login fails — closed, with no fallback authentication of any kind — and
 * {@link #isAvailable()} lets the login entry point say so instead of answering with a 500.
 * A successful result is cached for the life of the process; a failure is retried on the next
 * login attempt.
 *
 * <p><b>Client configuration.</b> Confidential client, {@code client_secret_basic}, authorization
 * code grant, PKCE (S256) required even though the client is confidential, and the provider must
 * advertise S256 or the registration is refused.
 */
public class DiscoveringClientRegistrationRepository implements ClientRegistrationRepository {

    private static final Logger log = LoggerFactory.getLogger(DiscoveringClientRegistrationRepository.class);

    private static final String REDIRECT_URI_TEMPLATE = "{baseUrl}/login/oauth2/code/{registrationId}";
    private static final String PKCE_S256 = "S256";

    private final SecurityProperties.Oidc properties;
    private final RestClient restClient;
    private final AtomicReference<ClientRegistration> registration = new AtomicReference<>();

    public DiscoveringClientRegistrationRepository(SecurityProperties.Oidc properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.discoveryTimeout());
        requestFactory.setReadTimeout(properties.discoveryTimeout());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        if (!properties.registrationId().equals(registrationId)) {
            return null;
        }
        ClientRegistration cached = registration.get();
        if (cached != null) {
            return cached;
        }
        if (!properties.isConfigured()) {
            log.warn("OIDC client is not configured (issuer, client id and client secret are required); "
                    + "login is disabled");
            return null;
        }
        try {
            ClientRegistration discovered = discover();
            registration.compareAndSet(null, discovered);
            return registration.get();
        } catch (RuntimeException e) {
            // No stack trace at WARN: the cause is almost always "Keycloak is not up yet".
            log.warn("OIDC discovery from {} failed; login is unavailable until it succeeds: {}",
                    properties.effectiveDiscoveryUri(), e.toString());
            log.debug("OIDC discovery failure", e);
            return null;
        }
    }

    /** True when a registration is available, attempting discovery if it has not succeeded yet. */
    public boolean isAvailable() {
        return findByRegistrationId(properties.registrationId()) != null;
    }

    /** The registration if discovery has already succeeded; never triggers a network call. */
    public ClientRegistration cachedRegistration() {
        return registration.get();
    }

    private ClientRegistration discover() {
        Map<String, Object> metadata = restClient.get()
                .uri(properties.effectiveDiscoveryUri())
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() { });
        if (metadata == null) {
            throw new IllegalStateException("Empty discovery document");
        }

        String expectedIssuer = properties.issuerUri().toString();
        Object advertisedIssuer = metadata.get("issuer");
        if (!expectedIssuer.equals(advertisedIssuer)) {
            // The one check that makes a separate discovery URI safe. Never relaxed.
            throw new IllegalStateException("Discovery document issuer '%s' does not match the configured issuer '%s'"
                    .formatted(advertisedIssuer, expectedIssuer));
        }
        if (!(metadata.get("code_challenge_methods_supported") instanceof List<?> methods)
                || !methods.contains(PKCE_S256)) {
            throw new IllegalStateException("The provider does not advertise PKCE S256");
        }

        ClientRegistration built = ClientRegistrations.fromOidcConfiguration(metadata)
                .registrationId(properties.registrationId())
                .clientId(properties.clientId())
                .clientSecret(properties.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(REDIRECT_URI_TEMPLATE)
                .scope(properties.scopes())
                .userNameAttributeName(IdentityMapper.SUBJECT_CLAIM)
                .clientName("Keycloak")
                // Spring applies PKCE automatically only to public clients. A confidential client
                // still benefits: it binds the code to this browser's login attempt, so an
                // intercepted code is useless even to someone holding the client secret.
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .build();
        log.info("OIDC provider discovered: issuer {}, authorization endpoint {}, token endpoint {}",
                built.getProviderDetails().getIssuerUri(),
                built.getProviderDetails().getAuthorizationUri(),
                built.getProviderDetails().getTokenUri());
        return built;
    }
}
