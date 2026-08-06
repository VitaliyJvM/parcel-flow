package ca.vm.parcelflow.tracking.failure;

import ca.vm.parcelflow.tracking.error.ErrorCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A carrier event the service could not process, kept so an operator can see what happened and act
 * on it.
 *
 * <p>Complements the dead letter topic rather than duplicating it. The topic holds the message and
 * is the thing you would replay in bulk; this row holds the explanation, the retry history and the
 * workflow state, which a topic cannot express and which an operator needs before deciding to
 * replay anything.
 *
 * <p>The stored {@code errorMessage} is bounded and the stack trace is deliberately absent — a
 * trace is unbounded, mostly framework frames, and belongs in the log stream where it can be
 * sampled and expired.
 */
@Entity
@Table(name = "failed_events")
public class FailedEvent {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 2000;
    private static final int MAX_ERROR_TYPE_LENGTH = 255;

    @Id
    @Column(name = "failed_event_id", nullable = false, updatable = false)
    private UUID failedEventId;

    /** Null when the payload could not be parsed far enough to find one. */
    @Column(name = "event_id", updatable = false)
    private UUID eventId;

    @Column(name = "shipment_id", updatable = false)
    private UUID shipmentId;

    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_category", nullable = false, length = 32)
    private ErrorCategory errorCategory;

    @Column(name = "error_type", nullable = false, length = MAX_ERROR_TYPE_LENGTH)
    private String errorType;

    @Column(name = "error_message", length = MAX_ERROR_MESSAGE_LENGTH)
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private FailedEventStatus status;

    @Column(name = "original_topic", nullable = false, updatable = false, length = 255)
    private String originalTopic;

    @Column(name = "original_partition", nullable = false, updatable = false)
    private int originalPartition;

    @Column(name = "original_offset", nullable = false, updatable = false)
    private long originalOffset;

    @Column(name = "first_failed_at", nullable = false, updatable = false)
    private Instant firstFailedAt;

    @Column(name = "last_failed_at", nullable = false)
    private Instant lastFailedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** For JPA only. */
    protected FailedEvent() {
    }

    private FailedEvent(Builder builder) {
        this.failedEventId = UUID.randomUUID();
        this.eventId = builder.eventId;
        this.shipmentId = builder.shipmentId;
        this.payload = Objects.requireNonNull(builder.payload, "payload");
        this.errorCategory = Objects.requireNonNull(builder.errorCategory, "errorCategory");
        this.errorType = truncate(
                Objects.requireNonNull(builder.errorType, "errorType"), MAX_ERROR_TYPE_LENGTH);
        this.errorMessage = truncate(builder.errorMessage, MAX_ERROR_MESSAGE_LENGTH);
        this.retryCount = 0;
        this.status = FailedEventStatus.FAILED;
        this.originalTopic = Objects.requireNonNull(builder.originalTopic, "originalTopic");
        this.originalPartition = builder.originalPartition;
        this.originalOffset = builder.originalOffset;
        this.firstFailedAt = Objects.requireNonNull(builder.failedAt, "failedAt");
        this.lastFailedAt = builder.failedAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Records that this event failed again, keeping {@code firstFailedAt} as the moment the problem
     * started. The distance between first and last is how an operator tells a one-off from
     * something that has been failing all week.
     */
    public void recordRepeatFailure(
            ErrorCategory category, String errorType, String errorMessage, Instant failedAt) {
        this.errorCategory = category;
        this.errorType = truncate(errorType, MAX_ERROR_TYPE_LENGTH);
        this.errorMessage = truncate(errorMessage, MAX_ERROR_MESSAGE_LENGTH);
        this.lastFailedAt = failedAt;
        this.status = FailedEventStatus.FAILED;
    }

    /** Called when a manual retry processed the event successfully. */
    public void markResolved() {
        this.status = FailedEventStatus.RESOLVED;
    }

    /** Called when a manual retry failed; the event returns to the queue of things to look at. */
    public void markRetryFailed(
            ErrorCategory category, String errorType, String errorMessage, Instant failedAt) {
        this.retryCount++;
        recordRepeatFailure(category, errorType, errorMessage, failedAt);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }

    public UUID getFailedEventId() {
        return failedEventId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getShipmentId() {
        return shipmentId;
    }

    public String getPayload() {
        return payload;
    }

    public ErrorCategory getErrorCategory() {
        return errorCategory;
    }

    public String getErrorType() {
        return errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public FailedEventStatus getStatus() {
        return status;
    }

    public String getOriginalTopic() {
        return originalTopic;
    }

    public int getOriginalPartition() {
        return originalPartition;
    }

    public long getOriginalOffset() {
        return originalOffset;
    }

    public Instant getFirstFailedAt() {
        return firstFailedAt;
    }

    public Instant getLastFailedAt() {
        return lastFailedAt;
    }

    public long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FailedEvent failedEvent
                && Objects.equals(failedEventId, failedEvent.failedEventId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(failedEventId);
    }

    /** Excludes the payload: it may be large and may contain a customer reference. */
    @Override
    public String toString() {
        return "FailedEvent[failedEventId=%s, eventId=%s, category=%s, status=%s, retryCount=%d]"
                .formatted(failedEventId, eventId, errorCategory, status, retryCount);
    }

    public static final class Builder {

        private UUID eventId;
        private UUID shipmentId;
        private String payload;
        private ErrorCategory errorCategory;
        private String errorType;
        private String errorMessage;
        private String originalTopic;
        private int originalPartition;
        private long originalOffset;
        private Instant failedAt;

        private Builder() {
        }

        public Builder eventId(UUID eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder shipmentId(UUID shipmentId) {
            this.shipmentId = shipmentId;
            return this;
        }

        public Builder payload(String payload) {
            this.payload = payload;
            return this;
        }

        public Builder errorCategory(ErrorCategory errorCategory) {
            this.errorCategory = errorCategory;
            return this;
        }

        public Builder errorType(String errorType) {
            this.errorType = errorType;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder origin(String topic, int partition, long offset) {
            this.originalTopic = topic;
            this.originalPartition = partition;
            this.originalOffset = offset;
            return this;
        }

        public Builder failedAt(Instant failedAt) {
            this.failedAt = failedAt;
            return this;
        }

        public FailedEvent build() {
            return new FailedEvent(this);
        }
    }
}
