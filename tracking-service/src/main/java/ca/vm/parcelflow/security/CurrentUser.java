package ca.vm.parcelflow.security;

import java.util.Objects;

/**
 * The authenticated person making a request, as the application's authorization rules see them.
 *
 * <p>Built only by {@link IdentityMapper} from claims of an ID token Spring Security has already
 * validated (signature, issuer, audience, expiry, nonce). Nothing in it comes from a request
 * parameter, a header or a body: a retailer id the browser sends is a value to be checked against
 * {@link #retailerId()}, never a source for it.
 *
 * @param subject     the OIDC {@code sub} claim — the stable identifier of the user at the issuer
 * @param username    {@code preferred_username}, for display and audit logs; never an authorization key
 * @param displayName {@code name}, falling back to the username
 * @param role        the single application role
 * @param retailerId  the retailer a {@link ParcelFlowRole#RETAILER} acts for; {@code null} for an admin
 */
public record CurrentUser(
        String subject,
        String username,
        String displayName,
        ParcelFlowRole role,
        String retailerId) {

    public CurrentUser {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(role, "role");
        if (role == ParcelFlowRole.RETAILER && retailerId == null) {
            // Unrepresentable on purpose: a retailer with no retailer must never reach a check
            // that might read "no retailer" as "no restriction".
            throw new IllegalArgumentException("A RETAILER user must have a retailer assignment");
        }
        if (role == ParcelFlowRole.ADMIN) {
            retailerId = null;
        }
    }

    public boolean isAdmin() {
        return role == ParcelFlowRole.ADMIN;
    }

    /**
     * The one retailer-isolation decision in the application. Every shipment read, list and write
     * that is subject to retailer scoping goes through here.
     */
    public boolean canAccessRetailer(String shipmentRetailerId) {
        if (isAdmin()) {
            return true;
        }
        return shipmentRetailerId != null && shipmentRetailerId.equals(retailerId);
    }
}
