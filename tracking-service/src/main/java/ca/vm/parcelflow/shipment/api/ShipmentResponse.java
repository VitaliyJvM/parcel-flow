package ca.vm.parcelflow.shipment.api;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.shipment.domain.Shipment;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read model for a shipment.
 *
 * <p>Deliberately omits {@code customerId}: the tracking view is fetched by whoever holds the
 * shipment id, and echoing a customer reference back adds no value while widening PII exposure.
 * {@code version} is exposed because it is a useful debugging signal for concurrent updates.
 */
@Schema(description = "Current tracking state of a shipment")
public record ShipmentResponse(
        UUID shipmentId,
        String retailerId,
        String trackingNumber,
        CarrierCode carrierCode,
        ShipmentStatus currentStatus,
        LocalDate estimatedDeliveryDate,
        Instant lastEventTime,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static ShipmentResponse from(Shipment shipment) {
        return new ShipmentResponse(
                shipment.getShipmentId(),
                shipment.getRetailerId(),
                shipment.getTrackingNumber(),
                shipment.getCarrierCode(),
                shipment.getCurrentStatus(),
                shipment.getEstimatedDeliveryDate(),
                shipment.getLastEventTime(),
                shipment.getVersion(),
                shipment.getCreatedAt(),
                shipment.getUpdatedAt());
    }
}
