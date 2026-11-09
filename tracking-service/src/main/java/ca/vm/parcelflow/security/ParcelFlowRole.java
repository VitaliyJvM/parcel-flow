package ca.vm.parcelflow.security;

import java.util.Optional;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The application roles. Exactly one is assigned to every user who may use ParcelFlow.
 *
 * <p>The names are the Keycloak realm role names, verbatim. They are mapped to Spring authorities
 * with the conventional {@code ROLE_} prefix so {@code hasRole("ADMIN")} reads naturally in the
 * security rules.
 */
public enum ParcelFlowRole {

    /** Operator: every retailer's shipments, failed-event diagnostics and retry. */
    ADMIN,

    /** A retailer's staff: that retailer's shipments only. */
    RETAILER;

    private static final String ROLE_PREFIX = "ROLE_";

    public String authority() {
        return ROLE_PREFIX + name();
    }

    public GrantedAuthority grantedAuthority() {
        return new SimpleGrantedAuthority(authority());
    }

    /** Case-sensitive on purpose: a role named {@code admin} is not this application's role. */
    public static Optional<ParcelFlowRole> fromClaimValue(String value) {
        for (ParcelFlowRole role : values()) {
            if (role.name().equals(value)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
