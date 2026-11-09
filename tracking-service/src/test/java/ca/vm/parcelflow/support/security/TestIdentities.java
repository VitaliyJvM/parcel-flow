package ca.vm.parcelflow.support.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import ca.vm.parcelflow.security.IdentityMapper;
import ca.vm.parcelflow.security.ParcelFlowRole;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Logged-in principals shaped exactly like the ones {@code ParcelFlowOidcUserService} produces,
 * for per-request use in MockMvc ({@code .with(TestIdentities.retailer("alpha-goods"))}).
 */
public final class TestIdentities {

    public static final String REGISTRATION_ID = "keycloak";

    private TestIdentities() {
    }

    public static RequestPostProcessor admin() {
        return authentication(principal("sub-admin", "ops.admin", ParcelFlowRole.ADMIN, null));
    }

    public static RequestPostProcessor retailer(String retailerId) {
        return authentication(principal(
                "sub-" + retailerId, "retailer." + retailerId, ParcelFlowRole.RETAILER, retailerId));
    }

    /**
     * The authentication a real OIDC login stores for a retailer, for tests that build a session by
     * hand (a {@code SecurityContext} in a {@code MockHttpSession}) rather than per request.
     */
    public static Authentication retailerAuthentication(String retailerId) {
        return principal("sub-" + retailerId, "retailer." + retailerId, ParcelFlowRole.RETAILER, retailerId);
    }

    /**
     * A principal with arbitrary claims and authorities, for proving that an identity the mapper
     * would refuse is refused even if it somehow reached the security context.
     */
    public static RequestPostProcessor withClaims(Map<String, Object> claims, String... authorities) {
        return authentication(token(claims, Set.of(authorities)));
    }

    static Authentication principal(
            String subject, String username, ParcelFlowRole role, String retailerId) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put(IdentityMapper.SUBJECT_CLAIM, subject);
        claims.put(IdentityMapper.USERNAME_CLAIM, username);
        claims.put(IdentityMapper.ROLES_CLAIM, List.of(role.name(), "offline_access", "default-roles-parcelflow"));
        if (retailerId != null) {
            claims.put(IdentityMapper.RETAILER_CLAIM, List.of(retailerId));
        }
        return token(claims, Set.of(role.authority()));
    }

    private static OAuth2AuthenticationToken token(Map<String, Object> claims, Set<String> authorities) {
        Instant now = Instant.now();
        OidcIdToken idToken = new OidcIdToken("test-id-token", now, now.plusSeconds(300), claims);
        var granted = authorities.stream()
                .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                .toList();
        DefaultOidcUser user = new DefaultOidcUser(granted, idToken, IdentityMapper.SUBJECT_CLAIM);
        return new OAuth2AuthenticationToken(user, granted, REGISTRATION_ID);
    }
}
