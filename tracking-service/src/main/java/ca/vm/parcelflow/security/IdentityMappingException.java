package ca.vm.parcelflow.security;

/**
 * The identity provider authenticated someone this application cannot authorize: no application
 * role, more than one, or a retailer user whose retailer assignment is missing, ambiguous or
 * malformed.
 *
 * <p>Always fatal for the login. There is no default role and no default retailer.
 */
public class IdentityMappingException extends RuntimeException {

    /** Machine-readable reason, safe to log. Never includes claim values. */
    public enum Reason {
        MISSING_SUBJECT,
        NO_APPLICATION_ROLE,
        MULTIPLE_APPLICATION_ROLES,
        MISSING_RETAILER_ASSIGNMENT,
        AMBIGUOUS_RETAILER_ASSIGNMENT,
        INVALID_RETAILER_ASSIGNMENT
    }

    private final Reason reason;

    public IdentityMappingException(Reason reason) {
        super("Identity cannot be authorized: " + reason);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
