package ca.vm.parcelflow.shipment;

import java.util.UUID;

/** Thrown when a shipment id does not resolve to a row. Mapped to HTTP 404. */
public class ShipmentNotFoundException extends RuntimeException {

    private final UUID shipmentId;

    public ShipmentNotFoundException(UUID shipmentId) {
        super("Shipment %s not found".formatted(shipmentId));
        this.shipmentId = shipmentId;
    }

    public UUID getShipmentId() {
        return shipmentId;
    }
}
