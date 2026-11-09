package ca.vm.parcelflow.support.security;

import ca.vm.parcelflow.security.ParcelFlowRole;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.test.context.support.WithSecurityContext;

/**
 * Runs a test (or every test in a class) as a logged-in ParcelFlow user.
 *
 * <p>The security context holds what a real login produces: an {@code OAuth2AuthenticationToken}
 * whose principal is an {@code OidcUser} with {@code sub}, {@code preferred_username}, {@code roles}
 * and {@code retailer_id} claims, and the single role authority. Authorization therefore runs through
 * the production {@code IdentityMapper}, not through a test shortcut.
 *
 * <p>For the protocol itself — redirects, PKCE, state, nonce, the code exchange — see
 * {@code KeycloakOidcLoginIntegrationTest}, which uses a real Keycloak instead.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@WithSecurityContext(factory = WithParcelFlowUserSecurityContextFactory.class)
public @interface WithParcelFlowUser {

    ParcelFlowRole role() default ParcelFlowRole.ADMIN;

    /** Required for {@link ParcelFlowRole#RETAILER}; ignored for an admin. */
    String retailerId() default "";

    String subject() default "";

    String username() default "test.user";
}
