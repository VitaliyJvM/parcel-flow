package ca.vm.parcelflow.tracking.api;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import ca.vm.parcelflow.tracking.domain.EventProcessingStatus;
import ca.vm.parcelflow.tracking.domain.TrackingEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * One entry in a parcel's tracking history.
 *
 * <p>Exposes both readings of the event: {@code normalizedEventType} is what ParcelFlow decided the
 * event means, {@code carrierEventType} is what the carrier actually sent. Showing both lets a
 * consumer render the normalized value while a support engineer can still see the raw scan.
 *
 * <p>The internal database id is not exposed — {@code eventId} is the identifier the carrier and
 * ParcelFlow share, and it is the one that means anything outside this process.
 */
@Schema(description = "A single carrier scan in a parcel's tracking history")
public record TrackingEventResponse(
        UUID eventId,

        @Schema(description = "ParcelFlow's normalized reading of the event", example = "OUT_FOR_DELIVERY")
        ShipmentStatus normalizedEventType,

        @Schema(description = "The carrier's own event code, as received", example = "SP_OFD")
        String carrierEventType,

        CarrierCode carrierCode,

        @Schema(description = "When the carrier observed the event (UTC)")
        Instant eventTime,

        @Schema(description = "When ParcelFlow ingested the event (UTC)")
        Instant receivedAt,

        @Schema(description = "The carrier's per-shipment ordering counter", example = "5")
        long sequenceNumber,

        String location,

        String description,

        @Schema(description = "Whether this event advanced the shipment status or was superseded")
        EventProcessingStatus processingStatus,

        String correlationId) {

    public static TrackingEventResponse from(TrackingEvent event) {
        return new TrackingEventResponse(
                event.getEventId(),
                event.getNormalizedEventType(),
                event.getCarrierEventType(),
                event.getCarrierCode(),
                event.getEventTime(),
                event.getReceivedAt(),
                event.getSequenceNumber(),
                event.getLocation(),
                event.getDescription(),
                event.getProcessingStatus(),
                event.getCorrelationId());
    }
}
