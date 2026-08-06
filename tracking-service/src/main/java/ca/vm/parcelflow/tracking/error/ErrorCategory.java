package ca.vm.parcelflow.tracking.error;

/**
 * Why an event could not be processed, and what can be done about it.
 *
 * <p>Two independent questions, because they have different answers:
 *
 * <ul>
 *   <li><b>Automatically retryable</b> — will re-running the same bytes, unchanged, seconds later
 *       plausibly succeed? Only transient conditions qualify.
 *   <li><b>Manually retryable</b> — could re-running it succeed after a human or a deployment
 *       changes something? A carrier without a normalizer becomes processable once the normalizer
 *       ships; a payload with a missing field never becomes processable.
 * </ul>
 *
 * <p>Blanket-retrying every {@code RuntimeException} would mean a malformed payload consumes the
 * full backoff budget on every redelivery while never having any chance of succeeding.
 */
public enum ErrorCategory {

    /** The payload could not be deserialized at all. */
    MALFORMED_PAYLOAD(false, false),

    /** A required field is missing, a constraint failed, or the schema version is unknown. */
    VALIDATION(false, false),

    /** The carrier sent an event code with no mapping. */
    UNKNOWN_EVENT_TYPE(false, false),

    /** The event's carrier disagrees with the shipment's. Retrying cannot reconcile that. */
    CARRIER_MISMATCH(false, false),

    /**
     * No normalizer is registered for the carrier. Not automatically retryable — no amount of
     * waiting adds a bean — but retryable by hand once the normalizer is deployed.
     */
    UNSUPPORTED_CARRIER(false, true),

    /**
     * The event references a shipment that does not exist.
     *
     * <p>ParcelFlow's policy is that this is <em>retryable</em>, which is a deliberate departure
     * from treating it as a plain data error. A carrier's first scan genuinely can beat the
     * retailer's registration call, and a few hundred milliseconds of backoff resolves that race
     * cheaply. When it is instead a bad shipment id, the bounded retries expire and the event is
     * dead-lettered — the cost of being wrong is a second of backoff, while the cost of the
     * opposite default is losing the first scan of a real parcel.
     */
    SHIPMENT_NOT_FOUND(true, true),

    /** Two threads updated one shipment. Retrying re-reads and re-decides, which is the fix. */
    CONCURRENCY_CONFLICT(true, true),

    /** The database or another dependency was temporarily unavailable. */
    INFRASTRUCTURE(true, true),

    /**
     * Unrecognised. Not retried automatically: an unclassified failure is as likely to be a bug
     * that will fail identically forever as it is to be transient, and an operator looking at the
     * failed-event record can make a better decision than a backoff policy can.
     */
    UNKNOWN(false, true);

    private final boolean retryableAutomatically;
    private final boolean retryableManually;

    ErrorCategory(boolean retryableAutomatically, boolean retryableManually) {
        this.retryableAutomatically = retryableAutomatically;
        this.retryableManually = retryableManually;
    }

    /** Whether the Kafka error handler should re-deliver the record before dead-lettering it. */
    public boolean isRetryableAutomatically() {
        return retryableAutomatically;
    }

    /** Whether {@code POST /api/admin/failed-events/{id}/retry} should accept this event. */
    public boolean isRetryableManually() {
        return retryableManually;
    }
}
