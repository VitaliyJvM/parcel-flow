package ca.vm.parcelflow.tracking;

import ca.vm.parcelflow.carrier.CarrierCode;
import java.util.UUID;

/**
 * Thrown when an event claims a different carrier than the shipment was registered with.
 *
 * <p>This means either a producer is publishing against the wrong shipment id or a shipment was
 * registered under the wrong carrier. Both are real problems, and applying the event anyway would
 * corrupt the parcel's history with another carrier's scans.
 */
public class CarrierMismatchException extends RuntimeException {

    public CarrierMismatchException(
            UUID shipmentId, CarrierCode shipmentCarrier, CarrierCode eventCarrier) {
        super("Shipment %s is registered with carrier %s but the event claims %s"
                .formatted(shipmentId, shipmentCarrier, eventCarrier));
    }
}
