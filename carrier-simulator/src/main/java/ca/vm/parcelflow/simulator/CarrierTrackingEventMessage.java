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
 *
 * <p>Every field is a reference type, including {@code schemaVersion} and {@code sequenceNumber}
 * which the consumer requires to be present. A producer that could not express a missing field
 * could not simulate the malformed traffic real carriers send, and the INVALID_EVENT scenario
 * depends on being able to.
 */
public record CarrierTrackingEventMessage(
        UUID eventId,
        Integer schemaVersion,
        UUID shipmentId,
        String trackingNumber,
        String carrierCode,
        String eventType,
        Instant eventTime,
        Long sequenceNumber,
        String location,
        String description,
        String correlationId) {

    public static final int SCHEMA_VERSION = 1;

    /** Returns a copy with a different event id, for republishing a scan as a distinct event. */
    public CarrierTrackingEventMessage withEventId(UUID newEventId) {
        return new CarrierTrackingEventMessage(newEventId, schemaVersion, shipmentId,
                trackingNumber, carrierCode, eventType, eventTime, sequenceNumber, location,
                description, correlationId);
    }
}
