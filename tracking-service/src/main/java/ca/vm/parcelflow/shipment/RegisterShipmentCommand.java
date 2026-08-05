package ca.vm.parcelflow.shipment;

import ca.vm.parcelflow.carrier.CarrierCode;
import java.time.LocalDate;

/**
 * Input to {@link ShipmentService#registerShipment}.
 *
 * <p>Exists so the service never sees a web request type: no validation annotations, no JSON
 * binding, and the same entry point can later be driven by a Kafka consumer or a batch import.
 */
public record RegisterShipmentCommand(
        String retailerId,
        String customerId,
        String trackingNumber,
        CarrierCode carrierCode,
        LocalDate estimatedDeliveryDate) {
}
