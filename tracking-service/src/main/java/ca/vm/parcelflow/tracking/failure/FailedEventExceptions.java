package ca.vm.parcelflow.tracking.failure;

import ca.vm.parcelflow.tracking.error.ErrorCategory;
import java.util.UUID;

/** Exceptions raised by the administrative failed-event API. */
public final class FailedEventExceptions {

    private FailedEventExceptions() {
    }

    /** Mapped to HTTP 404. */
    public static class NotFound extends RuntimeException {

        private final UUID failedEventId;

        public NotFound(UUID failedEventId) {
            super("Failed event %s not found".formatted(failedEventId));
            this.failedEventId = failedEventId;
        }

        public UUID getFailedEventId() {
            return failedEventId;
        }
    }

    /**
     * The event's failure category means reprocessing the same bytes can never succeed. Mapped to
     * HTTP 409 — the request is well-formed, the resource is just not in a retryable state.
     */
    public static class NotRetryable extends RuntimeException {

        private final ErrorCategory errorCategory;

        public NotRetryable(UUID failedEventId, ErrorCategory errorCategory) {
            super("Failed event %s has category %s, which cannot succeed on retry"
                    .formatted(failedEventId, errorCategory));
            this.errorCategory = errorCategory;
        }

        public ErrorCategory getErrorCategory() {
            return errorCategory;
        }
    }

    /**
     * Another retry already claimed this event, or it is already resolved. Mapped to HTTP 409.
     */
    public static class RetryNotAvailable extends RuntimeException {

        private final FailedEventStatus status;

        public RetryNotAvailable(UUID failedEventId, FailedEventStatus status) {
            super("Failed event %s is %s and cannot be retried now".formatted(failedEventId, status));
            this.status = status;
        }

        public FailedEventStatus getStatus() {
            return status;
        }
    }
}
