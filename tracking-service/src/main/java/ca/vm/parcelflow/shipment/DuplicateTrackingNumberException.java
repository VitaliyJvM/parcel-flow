package ca.vm.parcelflow.shipment;

import ca.vm.parcelflow.carrier.CarrierCode;

/**
 * Thrown when a shipment already exists for a (carrier, tracking number) pair. Mapped to HTTP 409.
 *
 * <p>Raised from the database unique constraint rather than a pre-check, so two concurrent creates
 * cannot both pass a {@code SELECT} and then both insert.
 *
 * <p>{@link #isExistingShipmentAccessible()} tells the API whether the caller may open the shipment
 * the registration collided with. It is decided by {@link ShipmentAccessService} after the failed
 * transaction has rolled back; a retailer is told only "accessible" or "not accessible", never whose
 * shipment it is or its id.
 */
public class DuplicateTrackingNumberException extends RuntimeException {

    private final CarrierCode carrierCode;
    private final String trackingNumber;
    private final boolean existingShipmentAccessible;

    public DuplicateTrackingNumberException(
            CarrierCode carrierCode, String trackingNumber, Throwable cause) {
        this(carrierCode, trackingNumber, false, cause);
    }

    private DuplicateTrackingNumberException(
            CarrierCode carrierCode, String trackingNumber, boolean existingShipmentAccessible, Throwable cause) {
        super("Shipment already exists for carrier %s and tracking number %s"
                .formatted(carrierCode, trackingNumber), cause);
        this.carrierCode = carrierCode;
        this.trackingNumber = trackingNumber;
        this.existingShipmentAccessible = existingShipmentAccessible;
    }

    /** A copy carrying the caller-specific visibility of the existing shipment. */
    public DuplicateTrackingNumberException withExistingShipmentAccessible(boolean accessible) {
        return new DuplicateTrackingNumberException(carrierCode, trackingNumber, accessible, this);
    }

    public CarrierCode getCarrierCode() {
        return carrierCode;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public boolean isExistingShipmentAccessible() {
        return existingShipmentAccessible;
    }
}
