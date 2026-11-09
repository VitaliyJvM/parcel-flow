package ca.vm.parcelflow.security;

import ca.vm.parcelflow.security.IdentityMappingException.Reason;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns validated OIDC claims into a {@link CurrentUser}, or refuses.
 *
 * <p><b>The single mechanism for retailer assignment.</b> A retailer user's retailer is the value
 * of the {@value #RETAILER_CLAIM} claim. Keycloak emits it from a user attribute of the same name,
 * which the realm's user profile declares as single-valued and editable by realm administrators
 * only — the user cannot set or change it from the account console. The attribute belongs to the
 * Keycloak user, whose stable identifier is the token's {@code sub}; usernames and e-mail addresses
 * are never used to look anything up.
 *
 * <p><b>Fail closed.</b> Every way the claims can be wrong is a refusal, never a default:
 * <ul>
 *   <li>no {@code sub};</li>
 *   <li>neither application role, or both (an admin who is also a retailer is ambiguous — which
 *       view of the data did the user mean?);</li>
 *   <li>a retailer with no assignment, more than one value (the mapper is configured
 *       multi-valued precisely so a second value shows up as an array here instead of being
 *       silently dropped by Keycloak), a non-string value, or a value that does not match
 *       {@link #RETAILER_ID_PATTERN}.</li>
 * </ul>
 *
 * <p>Pure and stateless so it can be exhaustively unit-tested and called both at login and on
 * every request.
 */
public final class IdentityMapper {

    /** Realm roles, emitted by a dedicated realm-role mapper into the ID token and userinfo. */
    public static final String ROLES_CLAIM = "roles";

    /** The retailer assignment, from the admin-managed {@code retailer_id} user attribute. */
    public static final String RETAILER_CLAIM = "retailer_id";

    public static final String SUBJECT_CLAIM = "sub";
    public static final String USERNAME_CLAIM = "preferred_username";
    public static final String NAME_CLAIM = "name";

    /**
     * Same width as {@code shipments.retailer_id} and {@code RegisterShipmentRequest.retailerId}.
     * Restricted to a conservative character set: the value is compared with stored data and
     * appears in log lines, and nothing about a retailer id needs whitespace or punctuation.
     */
    public static final Pattern RETAILER_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private IdentityMapper() {
    }

    public static CurrentUser fromClaims(Map<String, Object> claims) {
        String subject = stringClaim(claims, SUBJECT_CLAIM);
        if (subject == null || subject.isBlank()) {
            throw new IdentityMappingException(Reason.MISSING_SUBJECT);
        }

        ParcelFlowRole role = singleRole(claims.get(ROLES_CLAIM));
        String username = stringClaim(claims, USERNAME_CLAIM);
        String name = stringClaim(claims, NAME_CLAIM);
        String displayName = name != null && !name.isBlank() ? name : username;

        String retailerId = role == ParcelFlowRole.RETAILER ? retailerAssignment(claims.get(RETAILER_CLAIM)) : null;
        return new CurrentUser(subject, username, displayName, role, retailerId);
    }

    private static ParcelFlowRole singleRole(Object rolesClaim) {
        Set<ParcelFlowRole> roles = EnumSet.noneOf(ParcelFlowRole.class);
        // Keycloak's realm-role mapper also emits default roles (offline_access,
        // default-roles-<realm>, ...). Those are not this application's business and are ignored;
        // only exact matches of the application role names count.
        for (Object value : asCollection(rolesClaim)) {
            if (value instanceof String role) {
                ParcelFlowRole.fromClaimValue(role).ifPresent(roles::add);
            }
        }
        if (roles.isEmpty()) {
            throw new IdentityMappingException(Reason.NO_APPLICATION_ROLE);
        }
        if (roles.size() > 1) {
            throw new IdentityMappingException(Reason.MULTIPLE_APPLICATION_ROLES);
        }
        return roles.iterator().next();
    }

    private static String retailerAssignment(Object claim) {
        Collection<?> values = asCollection(claim);
        if (values.isEmpty()) {
            throw new IdentityMappingException(Reason.MISSING_RETAILER_ASSIGNMENT);
        }
        if (values.size() > 1) {
            throw new IdentityMappingException(Reason.AMBIGUOUS_RETAILER_ASSIGNMENT);
        }
        Object value = values.iterator().next();
        if (!(value instanceof String retailerId) || !RETAILER_ID_PATTERN.matcher(retailerId).matches()) {
            throw new IdentityMappingException(Reason.INVALID_RETAILER_ASSIGNMENT);
        }
        return retailerId;
    }

    private static Collection<?> asCollection(Object claim) {
        if (claim == null) {
            return List.of();
        }
        if (claim instanceof Collection<?> collection) {
            return collection;
        }
        return List.of(claim);
    }

    private static String stringClaim(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        return value instanceof String s ? s : null;
    }
}
