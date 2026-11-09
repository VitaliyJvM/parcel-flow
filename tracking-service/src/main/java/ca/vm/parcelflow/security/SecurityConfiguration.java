package ca.vm.parcelflow.security;

import ca.vm.parcelflow.security.web.IdentityProviderAvailabilityFilter;
import ca.vm.parcelflow.security.web.LoginFailureHandler;
import ca.vm.parcelflow.security.web.LoginPromptAuthorizationRequestResolver;
import ca.vm.parcelflow.security.web.ProblemSecurityHandlers;
import ca.vm.parcelflow.security.web.SessionLifetimeFilter;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Browser authentication and API authorization.
 *
 * <h2>Shape</h2>
 * This service is the Backend for Frontend. The Angular UI never sees a token: Spring Security runs
 * the OIDC authorization code flow (PKCE S256, confidential client) against Keycloak, keeps the
 * resulting tokens in the server-side HTTP session, and gives the browser one HttpOnly session
 * cookie. Every API call is authorized from that session.
 *
 * <h2>Rules, in evaluation order</h2>
 * <pre>
 *   /actuator/health/**, /actuator/info,       public — Compose health checks and the
 *   /actuator/prometheus                       Prometheus scrape; health details need ADMIN
 *   /v3/api-docs/**, /swagger-ui/**            public — the contract is not a secret, and CI
 *                                              compares it against the committed snapshot
 *   /api/auth/session, /api/auth/csrf          public — the UI asks "am I logged in?" before it is
 *   /login, /signed-out, /error                public
 *   /api/admin/**, /actuator/**, /internal/**  ADMIN
 *   /api/**                                    ADMIN or RETAILER; retailer scoping is enforced per
 *                                              resource in the services (see CurrentUser)
 *   everything else                            authenticated
 * </pre>
 *
 * <h2>Session lifetime</h2>
 * An authenticated session ends after {@code parcelflow.security.session.idle-timeout} (30 min)
 * without user activity or {@code absolute-timeout} (8 h) after login, whichever comes first; see
 * {@link SessionLifetimeFilter}. Keycloak-side changes (disabling a user, changing a role or
 * retailer) reach an existing session only through that bound: there is no back-channel logout.
 *
 * <h2>CSRF</h2>
 * The token lives in the HTTP session and is compared with the {@code X-XSRF-TOKEN} header (or the
 * {@code _csrf} form field) of every state-changing request. The UI fetches it from
 * {@code GET /api/auth/csrf}; it is never a cookie. A cookie-based (double-submit) token is only as
 * strong as the cookie's integrity, and a cookie can be planted by a sibling subdomain or, on
 * localhost, by any other local service: a token an attacker can set is a token an attacker knows.
 *
 * <h2>Responses</h2>
 * Under {@code /api/**}, {@code /actuator/**} and {@code /internal/**} a missing or expired session
 * is a 401 and a refusal a 403, both RFC 9457 problem documents — never a redirect to an HTML login
 * page, which an XHR cannot follow. Elsewhere an unauthenticated browser is sent to the UI's
 * {@code /login} route.
 *
 * <h2>What is deliberately not here</h2>
 * No resource-server (bearer token) support, no HTTP Basic, no form login, no remember-me: the only
 * way to authenticate is the OIDC login. Service-to-service authentication for the carrier simulator
 * (Phase 3) will be a separate, stateless filter chain with its own matcher; it must never accept
 * the browser session cookie, and this chain will never accept its bearer tokens.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {

    /** Spring Security's default authorization request base path. */
    public static final String AUTHORIZATION_REQUEST_BASE_URI = "/oauth2/authorization";

    private static final String ADMIN = ParcelFlowRole.ADMIN.name();
    private static final String RETAILER = ParcelFlowRole.RETAILER.name();

    @Bean
    DiscoveringClientRegistrationRepository clientRegistrationRepository(SecurityProperties properties) {
        return new DiscoveringClientRegistrationRepository(properties.oidc());
    }

    /**
     * Authorized clients (the access and refresh tokens) live in the HTTP session and die with it.
     * Boot's default would keep them in an in-memory map keyed by user name, which outlives the
     * session and is shared across a user's sessions.
     */
    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    @Bean
    SecurityFilterChain browserSecurityFilterChain(
            HttpSecurity http,
            SecurityProperties properties,
            DiscoveringClientRegistrationRepository clientRegistrations,
            OAuth2AuthorizedClientRepository authorizedClients,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
            @Value("${server.servlet.session.cookie.name:JSESSIONID}") String sessionCookieName,
            Clock clock) {

        RequestMatcher apiRequests = new OrRequestMatcher(
                path("/api/**"), path("/actuator/**"), path("/internal/**"));

        String registrationId = properties.oidc().registrationId();
        OidcClientInitiatedLogoutSuccessHandler logoutSuccessHandler =
                new OidcClientInitiatedLogoutSuccessHandler(clientRegistrations);
        // Where Keycloak sends the browser after ending its SSO session. Must match a
        // post.logout.redirect.uris entry on the client exactly.
        logoutSuccessHandler.setPostLogoutRedirectUri("{baseUrl}" + properties.oidc().postLogoutRedirectPath());
        // Used when there is no OIDC session to end, or the provider is unreachable: the local
        // session is already gone either way.
        logoutSuccessHandler.setDefaultTargetUrl(properties.oidc().postLogoutRedirectPath());

        // Not a bean: a Filter bean would also be registered with the servlet container, outside
        // the security chain.
        SessionLifetimeFilter sessionLifetime = new SessionLifetimeFilter(
                properties.session().idleTimeout(), properties.session().absoluteTimeout(), clock);

        SimpleUrlAuthenticationSuccessHandler redirectToRoot = new SimpleUrlAuthenticationSuccessHandler("/");
        // Always the UI root, never a "saved request": the UI restores its own route after login,
        // and a server-chosen redirect target is one less thing to get wrong.
        redirectToRoot.setAlwaysUseDefaultTargetUrl(true);
        AuthenticationSuccessHandler loginSuccessHandler = (request, response, authentication) -> {
            sessionLifetime.sessionStarted(request);
            redirectToRoot.onAuthenticationSuccess(request, response, authentication);
        };

        if (properties.requireHttps()) {
            http.redirectToHttps(https -> { });
        }

        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET,
                                "/actuator/health", "/actuator/health/**", "/actuator/info",
                                "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/session", "/api/auth/csrf").permitAll()
                        .requestMatchers("/login", "/signed-out", "/error").permitAll()
                        .requestMatchers("/api/admin/**", "/actuator/**", "/internal/**").hasRole(ADMIN)
                        .requestMatchers("/api/**").hasAnyRole(ADMIN, RETAILER)
                        .anyRequest().authenticated())

                .oauth2Login(login -> login
                        // The Angular route. Also disables Spring's generated login page.
                        .loginPage("/login")
                        .clientRegistrationRepository(clientRegistrations)
                        .authorizedClientRepository(authorizedClients)
                        .authorizationEndpoint(endpoint -> endpoint
                                .baseUri(AUTHORIZATION_REQUEST_BASE_URI)
                                .authorizationRequestResolver(new LoginPromptAuthorizationRequestResolver(
                                        clientRegistrations, AUTHORIZATION_REQUEST_BASE_URI)))
                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(new ParcelFlowOidcUserService()))
                        .successHandler(loginSuccessHandler)
                        .failureHandler(new LoginFailureHandler()))

                .logout(logout -> logout
                        // POST only: with CSRF protection on, Spring's LogoutFilter matches POST and
                        // requires the token, so a cross-site page cannot log the user out.
                        .logoutUrl("/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies(sessionCookieName)
                        .logoutSuccessHandler(logoutSuccessHandler))

                // Session-bound (synchronizer token), not a cookie: see the class comment. The
                // XOR request handler validates the BREACH-safe XOR-masked value, which is what
                // GET /api/auth/csrf hands out, from the header or the form field. The token is
                // loaded lazily, so a GET creates no session; Spring replaces it at login and the
                // session (and the token) is discarded at logout. Nothing is exempt: logout, admin
                // retry and every other state-changing request need it.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(sessionCsrfRepository())
                        .csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler()))

                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        // A new session id on login, so an id planted before login is worthless.
                        .sessionFixation(fixation -> fixation.changeSessionId()))

                .requestCache(cache -> cache.requestCache(new NullRequestCache()))

                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                ProblemSecurityHandlers.entryPoint(exceptionResolver), apiRequests)
                        .defaultAccessDeniedHandlerFor(
                                ProblemSecurityHandlers.accessDeniedHandler(exceptionResolver), apiRequests))

                .addFilterAfter(sessionLifetime, CsrfFilter.class)

                .addFilterBefore(
                        new IdentityProviderAvailabilityFilter(
                                clientRegistrations, AUTHORIZATION_REQUEST_BASE_URI + "/" + registrationId),
                        OAuth2AuthorizationRequestRedirectFilter.class);

        return http.build();
    }

    private static HttpSessionCsrfTokenRepository sessionCsrfRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        // The header name the UI, the scripts and the load test already send.
        repository.setHeaderName("X-XSRF-TOKEN");
        return repository;
    }

    private static RequestMatcher path(String pattern) {
        return PathPatternRequestMatcher.withDefaults().matcher(pattern);
    }
}
