package ca.vm.parcelflow.tracking.domain;

/**
 * What the service did with an event it accepted and stored.
 *
 * <p>Only outcomes that Stage 2 can actually produce are defined. Stage 3 adds the values that come
 * with duplicate detection and dead lettering; inventing them now would mean shipping constants
 * nothing can ever set.
 */
public enum EventProcessingStatus {

    /** Stored, and it advanced the shipment's current status. */
    APPLIED,

    /**
     * Stored, but the shipment's current status did not change: the event was older than what had
     * already been applied, or the shipment was already in a terminal state.
     *
     * <p>Not a failure. The event is a real observation and belongs in history — it just lost the
     * ordering comparison in {@code Shipment.recordEvent}.
     */
    SUPERSEDED
}
