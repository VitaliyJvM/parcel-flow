package ca.vm.parcelflow.tracking;

import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import ca.vm.parcelflow.tracking.domain.EventProcessingStatus;
import java.util.UUID;

/**
 * What happened to one event.
 *
 * <p>Returned rather than logged-and-discarded so the listener can report the outcome, and so
 * Stage 4 has something concrete to count for its metrics.
 */
public record TrackingEventProcessingResult(
        UUID eventId,
        UUID shipmentId,
        ShipmentStatus normalizedEventType,
        ShipmentStatus shipmentStatusAfterProcessing,
        EventProcessingStatus processingStatus) {

    public boolean advancedShipment() {
        return processingStatus == EventProcessingStatus.APPLIED;
    }
}
