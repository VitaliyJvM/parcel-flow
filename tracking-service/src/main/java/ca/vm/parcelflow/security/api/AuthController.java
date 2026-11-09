package ca.vm.parcelflow.security.api;

import ca.vm.parcelflow.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two endpoints the UI calls before it knows whether it is logged in.
 *
 * <p>Both are public and both are reads. Login itself is a browser navigation to
 * {@code /oauth2/authorization/keycloak}, and logout a POST to {@code /logout}; both are handled by
 * Spring Security filters, not by controllers.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Browser session state for the web UI")
@SecurityRequirements
public class AuthController {

    private final CurrentUserProvider currentUserProvider;

    public AuthController(CurrentUserProvider currentUserProvider) {
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Always 200, so "not logged in" is an answer rather than an error the UI has to tell apart from
     * a broken backend. A session that exists but whose identity cannot be authorized is reported as
     * not authenticated, the same as no session.
     */
    @GetMapping("/session")
    @Operation(
            summary = "Describe the current browser session",
            description = """
                    Returns `authenticated: false` when there is no authenticated session. Never \
                    returns tokens. Restores the UI's state after a page reload.""")
    public ResponseEntity<AuthSessionResponse> session() {
        AuthSessionResponse body = currentUserProvider.find()
                .map(AuthSessionResponse::of)
                .orElseGet(AuthSessionResponse::anonymous);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /**
     * The session's CSRF token, masked per response (BREACH-safe). The UI sends it in the
     * {@code X-XSRF-TOKEN} header of every state-changing API call, and as the {@code _csrf} field of
     * the logout form (a top-level POST, so the browser can follow the redirect to the identity
     * provider's end-session endpoint).
     *
     * <p>The token is stored in the server-side session and compared with what the request carries,
     * so a value planted in a cookie by another origin is worthless. Readable only same-origin: no
     * CORS is configured. A CSRF token is not an authentication credential: it is useless without
     * the session cookie, which script cannot read.
     */
    @GetMapping("/csrf")
    @Operation(
            summary = "Get the session's CSRF token",
            description = """
                    Send it as the `X-XSRF-TOKEN` header of every state-changing request, or as \
                    the `_csrf` field of a form POST (logout). Every value issued stays valid until \
                    the session ends or the user logs in or out.""")
    public ResponseEntity<CsrfTokenResponse> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CsrfTokenResponse(token.getHeaderName(), token.getParameterName(), token.getToken()));
    }
}
