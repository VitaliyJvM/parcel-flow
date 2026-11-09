package ca.vm.parcelflow.security.web;

import ca.vm.parcelflow.security.IdentityMappingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * Sends a failed OIDC login back to the UI's login page with a coarse, enumerable reason.
 *
 * <ul>
 *   <li>{@code not_authorized} — Keycloak authenticated the user but they have no usable ParcelFlow
 *       role or retailer assignment ({@link IdentityMappingException}).</li>
 *   <li>{@code login_failed} — anything else: invalid state, nonce or issuer, a rejected code
 *       exchange, the user cancelling at the provider.</li>
 * </ul>
 *
 * <p>The protocol error itself is logged, not echoed: the browser learns only which of two messages
 * to show. No session is left authenticated; Spring Security never stored an authentication for a
 * failed login.
 */
public class LoginFailureHandler implements AuthenticationFailureHandler {

    static final String NOT_AUTHORIZED = "/login?error=not_authorized";
    static final String LOGIN_FAILED = "/login?error=login_failed";

    private static final Logger log = LoggerFactory.getLogger(LoginFailureHandler.class);

    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        boolean notAuthorized = exception.getCause() instanceof IdentityMappingException;
        if (!notAuthorized) {
            log.warn("OIDC login failed: {}", exception.getMessage());
        }
        redirectStrategy.sendRedirect(request, response, notAuthorized ? NOT_AUTHORIZED : LOGIN_FAILED);
    }
}
