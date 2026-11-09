package ca.vm.parcelflow.security.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends an authenticated browser session that is too old or has been idle too long, before the
 * request is authorized.
 *
 * <h2>Why the container's session timeout is not enough</h2>
 * Tomcat's {@code server.servlet.session.timeout} is an idle timeout measured from the last
 * request of any kind. A page that polls in the background (the shipment detail page does)
 * refreshes it forever, and nothing bounds the total lifetime. Since the session carries the role
 * and retailer from the ID token at login, and Keycloak cannot push a revocation to this service,
 * an unbounded session means an unbounded delay before an account change takes effect.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li><b>Absolute:</b> a session ends {@code absoluteTimeout} after login, however active.</li>
 *   <li><b>Idle:</b> a session ends {@code idleTimeout} after the last request that was not
 *       marked as background ({@value #BACKGROUND_HEADER}{@code : true}). The UI marks automatic
 *       polling that way unless the user has interacted with the page since its previous request,
 *       so only real use keeps a session alive.</li>
 *   <li><b>Never unbounded:</b> the login success handler records the login time and the first
 *       activity ({@link #sessionStarted}). An authenticated session that has neither (the security
 *       context was stored by something other than the OIDC login, e.g. Spring Security Test) has
 *       never been measured, so it is given both on first sight and both limits apply from then
 *       on.</li>
 *   <li><b>Fail closed:</b> a session with only one of the two, or with a value that is not an
 *       {@link Instant}, was measured and then damaged. It is ended rather than restarted, because
 *       re-recording the login time would extend it.</li>
 * </ul>
 *
 * <p>Ending a session invalidates it and clears the security context, so the rest of the chain sees
 * an anonymous request: an API call gets the usual 401 problem document, {@code /api/auth/session}
 * answers {@code authenticated: false}, a navigation goes to the login page. The background header
 * is a hint about activity only; it never grants anything, and omitting it merely counts as
 * activity.
 *
 * <p>Registered after the CSRF filter, so a write sent with an expired session is answered 401
 * ("sign in again") rather than 403 (the token died with the session).
 */
public final class SessionLifetimeFilter extends OncePerRequestFilter {

    /** Request header the UI sets on automatic polling requests. */
    public static final String BACKGROUND_HEADER = "X-ParcelFlow-Background";

    public static final String AUTHENTICATED_AT = SessionLifetimeFilter.class.getName() + ".AUTHENTICATED_AT";
    public static final String LAST_ACTIVITY_AT = SessionLifetimeFilter.class.getName() + ".LAST_ACTIVITY_AT";

    private static final Logger log = LoggerFactory.getLogger(SessionLifetimeFilter.class);

    private final Duration idleTimeout;
    private final Duration absoluteTimeout;
    private final Clock clock;
    private final SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    public SessionLifetimeFilter(Duration idleTimeout, Duration absoluteTimeout, Clock clock) {
        this.idleTimeout = idleTimeout;
        this.absoluteTimeout = absoluteTimeout;
        this.clock = clock;
    }

    /**
     * Records the start of an authenticated session. Called once per successful login, after
     * Spring Security has rotated the session id and saved the security context.
     */
    public void sessionStarted(HttpServletRequest request) {
        Instant now = clock.instant();
        HttpSession session = request.getSession();
        session.setAttribute(AUTHENTICATED_AT, now);
        session.setAttribute(LAST_ACTIVITY_AT, now);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && isAuthenticated(session)) {
            Instant now = clock.instant();
            if (session.getAttribute(AUTHENTICATED_AT) == null && session.getAttribute(LAST_ACTIVITY_AT) == null) {
                log.warn("Authenticated session without a recorded login time; starting its lifetime now");
                session.setAttribute(AUTHENTICATED_AT, now);
                session.setAttribute(LAST_ACTIVITY_AT, now);
            }
            String expiry = expiry(session, now);
            if (expiry != null) {
                end(session, expiry);
            } else if (!isBackground(request)) {
                session.setAttribute(LAST_ACTIVITY_AT, now);
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * The reason the session must end now, or null if it may continue. A missing or malformed
     * timestamp is a reason to end it: the limits cannot be evaluated, so the session is not trusted.
     */
    private String expiry(HttpSession session, Instant now) {
        Instant authenticatedAt = instant(session, AUTHENTICATED_AT);
        Instant lastActivityAt = instant(session, LAST_ACTIVITY_AT);
        if (authenticatedAt == null || lastActivityAt == null) {
            return "session lifetime record missing or malformed";
        }
        if (!now.isBefore(authenticatedAt.plus(absoluteTimeout))) {
            return "absolute lifetime of " + absoluteTimeout + " reached";
        }
        if (!now.isBefore(lastActivityAt.plus(idleTimeout))) {
            return "idle for " + idleTimeout;
        }
        return null;
    }

    private void end(HttpSession session, String reason) {
        try {
            session.invalidate();
        } catch (IllegalStateException _) {
            // Invalidated concurrently (logout in another request): the outcome is the same.
        }
        securityContextHolderStrategy.clearContext();
        log.info("Ended an authenticated session: {}", reason);
    }

    private static boolean isAuthenticated(HttpSession session) {
        try {
            return session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) != null;
        } catch (IllegalStateException _) {
            return false;
        }
    }

    private static boolean isBackground(HttpServletRequest request) {
        return "true".equalsIgnoreCase(request.getHeader(BACKGROUND_HEADER));
    }

    private static Instant instant(HttpSession session, String name) {
        return session.getAttribute(name) instanceof Instant value ? value : null;
    }
}
