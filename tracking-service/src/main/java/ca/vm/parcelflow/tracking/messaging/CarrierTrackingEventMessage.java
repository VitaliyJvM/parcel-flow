package ca.vm.parcelflow.tracking.messaging;

import ca.vm.parcelflow.carrier.CarrierCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * The wire contract for a carrier tracking event, as published to
 * {@code carrier-tracking-events}.
 *
 * <p>This is a copy of the contract, not a shared class. The producer — a carrier, simulated here —
 * has its own definition, exactly as it would if a third party were publishing. Sharing a jar
 * between producer and consumer makes a schema change look like a compile step instead of a
 * deployment problem, which hides the whole reason {@link #schemaVersion} exists.
 *
 * <p>Consequently the consumer never trusts a type header to tell it which class to instantiate;
 * the deserializer is pinned to this type. See {@code spring.json.use.type.headers} in
 * {@code application.yml}.
 *
 * <p>All timestamps are UTC instants. Validation annotations describe what the processor requires;
 * they are enforced explicitly in {@code TrackingEventProcessor} rather than by the listener, so a
 * violation is a domain error that Stage 3 can route to the dead letter topic.
 */
public record CarrierTrackingEventMessage(

        @NotNull UUID eventId,

        /**
         * Contract version. Bumped when a change is not backward compatible; the consumer rejects
         * versions it does not understand rather than silently reading a field that has moved.
         */
        @NotNull @Positive Integer schemaVersion,

        @NotNull UUID shipmentId,

        @NotBlank @Size(max = 128) String trackingNumber,

        @NotNull CarrierCode carrierCode,

        /** The carrier's own event code, e.g. {@code SP_OFD}. Normalized on arrival. */
        @NotBlank @Size(max = 64) String eventType,

        /** When the carrier observed the event, in UTC. Not when we received it. */
        @NotNull Instant eventTime,

        /** The carrier's per-shipment monotonic counter. Primary ordering authority. */
        @NotNull @Positive Long sequenceNumber,

        @Size(max = 255) String location,

        @Size(max = 512) String description,

        /** Propagated from the producer so one parcel's journey is traceable across both services. */
        @NotBlank @Size(max = 64) String correlationId) {

    /** The only schema version this service understands. */
    public static final int SUPPORTED_SCHEMA_VERSION = 1;
}
