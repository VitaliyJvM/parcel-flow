package ca.vm.parcelflow.simulator;

import java.time.Instant;
import java.util.UUID;

/**
 * The producer's view of the carrier tracking event contract.
 *
 * <p>Intentionally a separate declaration from the tracking service's copy. A real carrier does not
 * compile against your classes; the contract between them is the JSON on the topic and the
 * {@code schemaVersion} field that describes it. Sharing a module here would make schema evolution
 * feel like a compile-time concern and quietly remove the problem this project exists to
 * demonstrate.
 *
 * <p>The price is that the two declarations must be kept in step by hand. That is the real cost of
 * an unversioned JSON contract, and the argument for the schema registry listed as a future
 * improvement.
 */
public record CarrierTrackingEventMessage(
        UUID eventId,
        int schemaVersion,
        UUID shipmentId,
        String trackingNumber,
        String carrierCode,
        String eventType,
        Instant eventTime,
        long sequenceNumber,
        String location,
        String description,
        String correlationId) {

    public static final int SCHEMA_VERSION = 1;
}
