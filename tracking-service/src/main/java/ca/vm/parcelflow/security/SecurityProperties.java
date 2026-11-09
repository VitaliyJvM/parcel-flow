package ca.vm.parcelflow.security;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Browser authentication settings ({@code parcelflow.security.*}).
 *
 * <p>Defaults are the production-safe values. The {@code local} profile relaxes exactly the two
 * that cannot work over plain HTTP on a laptop (HTTPS redirection and, in {@code application-local.yml},
 * the {@code Secure} cookie flag) and nothing else.
 *
 * @param requireHttps redirect plain-HTTP requests to HTTPS. Behind a TLS-terminating proxy this
 *                     relies on {@code X-Forwarded-Proto} (see {@code server.forward-headers-strategy}).
 * @param oidc         the OpenID Provider and this application's confidential client
 * @param session      lifetime limits of an authenticated browser session
 */
@ConfigurationProperties(prefix = "parcelflow.security")
public record SecurityProperties(
        @DefaultValue("true") boolean requireHttps,
        @DefaultValue Oidc oidc,
        @DefaultValue Session session) {

    /**
     * Enforced by {@code SessionLifetimeFilter} on every request, in addition to the servlet
     * container's own idle timeout ({@code server.servlet.session.timeout}).
     *
     * @param idleTimeout     an authenticated session ends after this long without user activity.
     *                        Requests the UI marks as background polling do not count as activity,
     *                        so an open page cannot keep a session alive on its own.
     * @param absoluteTimeout an authenticated session ends this long after login, however active it
     *                        is. Bounds how long a role, retailer or account change in Keycloak can
     *                        take to reach an existing session (there is no back-channel logout).
     */
    public record Session(
            @DefaultValue("30m") Duration idleTimeout,
            @DefaultValue("8h") Duration absoluteTimeout) {

        public Session {
            requirePositive(idleTimeout, "idle-timeout");
            requirePositive(absoluteTimeout, "absolute-timeout");
        }

        private static void requirePositive(Duration value, String name) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException("parcelflow.security.session." + name + " must be positive");
            }
        }
    }

    /**
     * @param registrationId Spring Security's id for the client registration; also the last path
     *                       segment of the login ({@code /oauth2/authorization/{id}}) and callback
     *                       ({@code /login/oauth2/code/{id}}) URLs
     * @param issuerUri      the issuer identifier exactly as the provider publishes it and as the
     *                       browser reaches it. The ID token's {@code iss} and the discovery
     *                       document's {@code issuer} must both equal it, character for character.
     * @param discoveryUri   where this service fetches the discovery document. Defaults to
     *                       {@code issuerUri + /.well-known/openid-configuration}. Set only when the
     *                       service reaches the provider by a different network path from the
     *                       browser — inside Docker Compose, {@code http://keycloak:8080/...} — and
     *                       the provider is configured to publish its public issuer regardless.
     *                       It changes where metadata is fetched from, never what issuer is accepted.
     * @param clientId       the confidential client's id
     * @param clientSecret   the confidential client's secret; from the environment only
     * @param scopes         requested scopes; {@code openid} is required
     * @param postLogoutRedirectPath where the provider returns the browser after RP-initiated
     *                       logout, relative to this application's base URL. Must be registered
     *                       exactly on the client in Keycloak.
     * @param discoveryTimeout connect and read timeout for the discovery request
     */
    public record Oidc(
            @DefaultValue("keycloak") String registrationId,
            URI issuerUri,
            URI discoveryUri,
            String clientId,
            String clientSecret,
            @DefaultValue({"openid", "profile", "email"}) List<String> scopes,
            @DefaultValue("/signed-out") String postLogoutRedirectPath,
            @DefaultValue("5s") Duration discoveryTimeout) {

        private static final String WELL_KNOWN = "/.well-known/openid-configuration";

        public Oidc {
            // An unset environment placeholder binds as an empty URI, not null.
            issuerUri = blankToNull(issuerUri);
            discoveryUri = blankToNull(discoveryUri);
        }

        public URI effectiveDiscoveryUri() {
            if (discoveryUri != null) {
                return discoveryUri;
            }
            String issuer = issuerUri.toString();
            return URI.create((issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer) + WELL_KNOWN);
        }

        public boolean isConfigured() {
            return issuerUri != null && hasText(clientId) && hasText(clientSecret);
        }

        private static URI blankToNull(URI uri) {
            return uri == null || uri.toString().isBlank() ? null : uri;
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }
}
