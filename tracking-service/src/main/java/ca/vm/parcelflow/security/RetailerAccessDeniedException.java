package ca.vm.parcelflow.security;

/**
 * A retailer user asked to act for a retailer other than their own: list another retailer's
 * shipments, or register a shipment under another retailer's id. Mapped to HTTP 403.
 *
 * <p>403 rather than 404 because nothing is disclosed: the caller named the retailer themselves,
 * and the answer is the same whether or not that retailer has any shipments. Reads of a specific
 * shipment are different — see {@code ShipmentAccessService}.
 */
public class RetailerAccessDeniedException extends RuntimeException {

    public RetailerAccessDeniedException() {
        super("Not permitted to act for this retailer");
    }
}
