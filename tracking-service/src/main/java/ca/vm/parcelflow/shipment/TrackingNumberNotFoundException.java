package ca.vm.parcelflow.shipment;

import ca.vm.parcelflow.carrier.CarrierCode;

/**
 * Thrown when no shipment is registered for a (carrier, tracking number) pair. Mapped to HTTP 404
 * with the same {@code shipment-not-found} problem type as a lookup by id: to a client both mean
 * "there is no shipment at this address", and they differ only in which identifier was used.
 *
 * <p>Kept separate from {@link ShipmentNotFoundException} because that exception's contract is a
 * non-null shipment id, which a tracking-number lookup does not have.
 */
public class TrackingNumberNotFoundException extends RuntimeException {

    private final CarrierCode carrierCode;
    private final String trackingNumber;

    public TrackingNumberNotFoundException(CarrierCode carrierCode, String trackingNumber) {
        super("No shipment found for carrier %s and tracking number %s"
                .formatted(carrierCode, trackingNumber));
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
