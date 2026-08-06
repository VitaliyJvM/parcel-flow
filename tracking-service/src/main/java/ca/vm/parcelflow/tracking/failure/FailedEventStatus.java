package ca.vm.parcelflow.tracking.failure;

/** Operator workflow state of a failed event. */
public enum FailedEventStatus {

    /** Automatic retries were exhausted, or the failure was never retryable. Awaiting a decision. */
    FAILED,

    /**
     * A manual retry is in flight.
     *
     * <p>Exists so a second retry request can be rejected rather than reprocessing the same event
     * concurrently with the first.
     */
    RETRYING,

    /** A manual retry processed the event successfully. */
    RESOLVED
}
