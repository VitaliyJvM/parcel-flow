package ca.vm.parcelflow.security;

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Spring Security's standard OIDC user service, plus the application's authorization mapping.
 *
 * <p>By the time {@link #loadUser} runs, Spring Security has exchanged the code (with the PKCE
 * verifier and client authentication), validated the ID token's signature against the issuer's
 * JWKS, its {@code iss}, {@code aud}, {@code azp}, expiry and {@code nonce}, and — through the
 * superclass — fetched userinfo and checked its {@code sub} matches. This class adds only the
 * decision "may this person use ParcelFlow, and as what".
 *
 * <p>A refusal is an {@link OAuth2AuthenticationException}, so the login fails before any
 * authenticated session exists: no session id is issued for an identity that cannot be authorized.
 *
 * <p>The returned user carries exactly one authority, the application role. Spring's default
 * {@code OIDC_USER} and {@code SCOPE_*} authorities are dropped deliberately so nothing can come
 * to depend on them by accident.
 */
public class ParcelFlowOidcUserService extends OidcUserService {

    /** OAuth2 error code surfaced on the login page as "not authorized for ParcelFlow". */
    public static final String NOT_AUTHORIZED_ERROR = OAuth2ErrorCodes.ACCESS_DENIED;

    private static final Logger log = LoggerFactory.getLogger(ParcelFlowOidcUserService.class);

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) {
        OidcUser oidcUser = super.loadUser(userRequest);
        CurrentUser user;
        try {
            user = IdentityMapper.fromClaims(oidcUser.getClaims());
        } catch (IdentityMappingException e) {
            // The subject is the stable, non-personal identifier an administrator needs to fix the
            // assignment in Keycloak. Claim values are not logged.
            log.warn("Login refused for subject {}: {}", oidcUser.getSubject(), e.getReason());
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(NOT_AUTHORIZED_ERROR, "Not authorized for ParcelFlow", null), e);
        }
        log.info("Login accepted for subject {} as {}", user.subject(), user.role());
        return new DefaultOidcUser(
                Set.of(user.role().grantedAuthority()),
                oidcUser.getIdToken(),
                oidcUser.getUserInfo(),
                IdentityMapper.SUBJECT_CLAIM);
    }
}
