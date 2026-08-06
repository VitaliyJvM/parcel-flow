package ca.vm.parcelflow.tracking.failure.api;

import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import ca.vm.parcelflow.tracking.domain.EventProcessingStatus;
import ca.vm.parcelflow.tracking.failure.FailedEventService.FailedEventRetryOutcome;
import ca.vm.parcelflow.tracking.failure.FailedEventStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * The outcome of a manual retry.
 *
 * <p>Reports what actually happened rather than just "accepted", which is the reason the retry
 * reprocesses in-process instead of republishing to Kafka.
 */
@Schema(description = "Result of reprocessing a failed event")
public record FailedEventRetryResponse(
        UUID failedEventId,
        boolean succeeded,
        FailedEventStatus status,

        @Schema(description = "How the event was recorded, when the retry succeeded")
        EventProcessingStatus processingStatus,

        @Schema(description = "The shipment's status after reprocessing, when the retry succeeded")
        ShipmentStatus shipmentStatus,

        @Schema(description = "True when the event turned out to be already stored — still a success")
        Boolean duplicate,

        @Schema(description = "Human-readable summary of the outcome")
        String detail) {

    public static FailedEventRetryResponse from(FailedEventRetryOutcome outcome) {
        if (outcome.succeeded()) {
            var result = outcome.result();
            return new FailedEventRetryResponse(
                    outcome.failedEventId(),
                    true,
                    FailedEventStatus.RESOLVED,
                    result.processingStatus(),
                    result.shipmentStatusAfterProcessing(),
                    result.duplicate(),
                    result.duplicate()
                            ? "Event was already stored; nothing changed"
                            : "Event reprocessed and recorded as " + result.processingStatus());
        }
        return new FailedEventRetryResponse(
                outcome.failedEventId(),
                false,
                FailedEventStatus.FAILED,
                null,
                null,
                null,
                "Reprocessing failed again with category " + outcome.errorCategory());
    }
}
