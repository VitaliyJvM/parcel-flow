package ca.vm.parcelflow.tracking.failure.api;

import ca.vm.parcelflow.tracking.error.ErrorCategory;
import ca.vm.parcelflow.tracking.failure.FailedEvent;
import ca.vm.parcelflow.tracking.failure.FailedEventStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * A failed event, as an operator sees it.
 *
 * <p>Two things are deliberately absent. There is no stack trace: the exception type and message
 * say what went wrong, and a trace in an HTTP response is an information leak that tells a caller
 * the internal class layout. There is no payload either — it may carry a customer reference, and
 * the listing exists to triage, not to dump message bodies.
 */
@Schema(description = "A carrier event that could not be processed")
public record FailedEventResponse(
        UUID failedEventId,

        @Schema(description = "The carrier's event id, absent when the payload could not be parsed")
        UUID eventId,

        UUID shipmentId,
        ErrorCategory errorCategory,

        @Schema(description = "Whether the automatic retry policy considers this worth re-delivering")
        boolean retryableAutomatically,

        @Schema(description = "Whether POST .../retry will accept this event")
        boolean retryableManually,

        @Schema(description = "Exception class name", example = "ca.vm.parcelflow.tracking.InvalidCarrierEventException")
        String errorType,

        @Schema(description = "Exception message, truncated. No stack trace.")
        String errorMessage,

        int retryCount,
        FailedEventStatus status,
        String originalTopic,
        int originalPartition,
        long originalOffset,
        Instant firstFailedAt,
        Instant lastFailedAt) {

    public static FailedEventResponse from(FailedEvent failedEvent) {
        ErrorCategory category = failedEvent.getErrorCategory();
        return new FailedEventResponse(
                failedEvent.getFailedEventId(),
                failedEvent.getEventId(),
                failedEvent.getShipmentId(),
                category,
                category.isRetryableAutomatically(),
                category.isRetryableManually(),
                failedEvent.getErrorType(),
                failedEvent.getErrorMessage(),
                failedEvent.getRetryCount(),
                failedEvent.getStatus(),
                failedEvent.getOriginalTopic(),
                failedEvent.getOriginalPartition(),
                failedEvent.getOriginalOffset(),
                failedEvent.getFirstFailedAt(),
                failedEvent.getLastFailedAt());
    }
}
