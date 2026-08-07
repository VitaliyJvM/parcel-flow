package ca.vm.parcelflow.simulator;

import java.util.List;
import java.util.Locale;

/**
 * What the simulator publishes.
 *
 * <p>Each scenario provokes one behaviour in the tracking service, so a reviewer can watch a single
 * mechanism work rather than inferring it from a log.
 */
public enum Scenario {

    /** The carrier's normal journey, label to doorstep. */
    NORMAL("Publishes the carrier's full journey once."),

    /**
     * The normal journey, with several events published twice — same {@code eventId}, byte for
     * byte, which is exactly what a Kafka redelivery or a carrier's own retry looks like.
     *
     * <p>Expected: history and notification counts match the NORMAL run.
     */
    DUPLICATE("Republishes some events verbatim to exercise idempotency."),

    /**
     * The journey with its middle deliberately shuffled, so lower sequence numbers arrive after
     * higher ones.
     *
     * <p>Expected: every event in history, the shipment's status decided by the highest sequence,
     * and no notification for the stale milestones.
     */
    OUT_OF_ORDER("Publishes the journey with the middle events reordered."),

    /**
     * A structurally invalid event: an unsupported schema version and a blank correlation id.
     *
     * <p>Expected: classified VALIDATION, not retried, recorded in {@code failed_events} and
     * published to the dead letter topic.
     */
    INVALID_EVENT("Publishes an event that fails validation."),

    /**
     * An event whose carrier code has no mapping in the carrier's normalizer.
     *
     * <p>Expected: classified UNKNOWN_EVENT_TYPE, and — unlike INVALID_EVENT — visibly distinct in
     * the failed-event record, because the two need different fixes.
     */
    UNKNOWN_CARRIER_EVENT("Publishes an event whose carrier code has no mapping."),

    /**
     * The whole journey published back-to-back with no delay.
     *
     * <p>Worth being precise about what this does and does not show. Every event for one parcel
     * carries the same partition key, so the broker delivers them to one consumer thread in order:
     * this burst does <em>not</em> produce concurrent updates to a single shipment, and it cannot,
     * by design. What it exercises is the ingest path under a burst, and per-parcel ordering
     * holding up when the producer stops pacing itself.
     *
     * <p>Genuine optimistic-lock contention is exercised by the concurrency integration test, which
     * calls the processor from several threads directly — the only way to reach that path, since
     * Kafka's partitioning exists precisely to prevent it.
     */
    RAPID_CONCURRENT_EVENTS("Publishes the journey with no delay between events.");

    private final String description;

    Scenario(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    public static Scenario parse(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        for (Scenario scenario : values()) {
            if (scenario.name().equals(normalized)) {
                return scenario;
            }
        }
        throw new IllegalArgumentException(
                "Unknown --scenario '%s'. Supported: %s".formatted(value, List.of(values())));
    }
}
