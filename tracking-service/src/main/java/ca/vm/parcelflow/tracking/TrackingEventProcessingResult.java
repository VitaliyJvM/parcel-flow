package ca.vm.parcelflow.tracking;

import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import ca.vm.parcelflow.tracking.domain.EventProcessingStatus;
import java.util.UUID;

/**
 * What happened to one event.
 *
 * @param processingStatus the status of the stored history row. On a duplicate delivery this is
 *     the status recorded the <em>first</em> time the event was processed, not a new decision.
 * @param duplicate whether this particular delivery was a replay of an event already stored. A
 *     duplicate is a successful no-op, not a failure — the work was already done.
 * @param attempts how many times the transaction ran, including the successful one. Greater than
 *     one means an optimistic-lock conflict was retried.
 */
public record TrackingEventProcessingResult(
        UUID eventId,
        UUID shipmentId,
        ShipmentStatus normalizedEventType,
        ShipmentStatus shipmentStatusAfterProcessing,
        EventProcessingStatus processingStatus,
        boolean duplicate,
        int attempts) {

    /** True when this delivery advanced the shipment. False for duplicates and superseded events. */
    public boolean advancedShipment() {
        return !duplicate && processingStatus == EventProcessingStatus.APPLIED;
    }
}
