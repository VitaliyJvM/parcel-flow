package ca.vm.parcelflow.shipment;

import ca.vm.parcelflow.carrier.CarrierCode;

/**
 * Thrown when a shipment already exists for a (carrier, tracking number) pair. Mapped to HTTP 409.
 *
 * <p>Raised from the database unique constraint rather than a pre-check, so two concurrent creates
 * cannot both pass a {@code SELECT} and then both insert.
 */
public class DuplicateTrackingNumberException extends RuntimeException {

    private final CarrierCode carrierCode;
    private final String trackingNumber;

    public DuplicateTrackingNumberException(
            CarrierCode carrierCode, String trackingNumber, Throwable cause) {
        super("Shipment already exists for carrier %s and tracking number %s"
                .formatted(carrierCode, trackingNumber), cause);
        this.carrierCode = carrierCode;
        this.trackingNumber = trackingNumber;
    }

    public CarrierCode getCarrierCode() {
        return carrierCode;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }
}
