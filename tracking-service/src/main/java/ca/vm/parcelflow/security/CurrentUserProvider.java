package ca.vm.parcelflow.security;

import java.util.Optional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@link CurrentUser} of the request being served.
 *
 * <p>Re-derives the user from the session's validated OIDC claims on every call rather than
 * trusting the authorities computed at login. The mapping is cheap and pure, and re-running it
 * means a principal that somehow reached the security context without passing
 * {@link ParcelFlowOidcUserService} still cannot be read as "no retailer restriction".
 *
 * <p>Only an OIDC login is accepted. This service authenticates browsers in exactly one way; a
 * different principal type here would mean a misconfiguration, and the answer to that is a
 * refusal.
 *
 * <p>Never consulted by Kafka consumers or other background work: they have no user and need no
 * authorization decision.
 */
@Component
public class CurrentUserProvider {

    private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

    /** The current user, if the request is authenticated with an authorizable OIDC identity. */
    public Optional<CurrentUser> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof OidcUser oidcUser)) {
            return Optional.empty();
        }
        try {
            return Optional.of(IdentityMapper.fromClaims(oidcUser.getClaims()));
        } catch (IdentityMappingException _) {
            return Optional.empty();
        }
    }

    /**
     * @throws AuthenticationCredentialsNotFoundException if the request is not authenticated
     * @throws AccessDeniedException if it is, but not as a user this application can authorize
     */
    public CurrentUser require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // The anonymous token reports isAuthenticated() == true; it is still not a login.
        if (authentication == null || !authentication.isAuthenticated()
                || TRUST_RESOLVER.isAnonymous(authentication)) {
            throw new AuthenticationCredentialsNotFoundException("Not authenticated");
        }
        return find().orElseThrow(() -> new AccessDeniedException("Not authorized for ParcelFlow"));
    }
}
