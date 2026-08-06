package ca.vm.parcelflow.tracking.domain;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One observation reported by a carrier, stored exactly as received plus the normalized reading of
 * it.
 *
 * <p>Append-only and immutable after construction: there are no setters, and nothing in the service
 * mutates a stored event. An event is a statement about the past, so correcting one would mean
 * rewriting history rather than appending to it.
 *
 * <p>Both the carrier's own event code and the normalized status are kept. The normalized value is
 * what the API and the shipment state use; the raw value is what makes a support conversation with
 * the carrier possible, and what allows a mapping bug to be re-run against stored history.
 *
 * <p>{@code shipmentId} is a plain {@code UUID}, not a {@code @ManyToOne}. An association would add
 * lazy loading and cascade semantics to an append-only table for no gain — the write path already
 * holds the {@code Shipment} it needs, and the read path never navigates back.
 */
@Entity
@Table(name = "tracking_events")
public class TrackingEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "shipment_id", nullable = false, updatable = false)
    private UUID shipmentId;

    @Column(name = "tracking_number", nullable = false, updatable = false, length = 128)
    private String trackingNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "carrier_code", nullable = false, updatable = false, length = 32)
    private CarrierCode carrierCode;

    @Column(name = "carrier_event_type", nullable = false, updatable = false, length = 64)
    private String carrierEventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "normalized_event_type", nullable = false, updatable = false, length = 32)
    private ShipmentStatus normalizedEventType;

    @Column(name = "event_time", nullable = false, updatable = false)
    private Instant eventTime;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "sequence_number", nullable = false, updatable = false)
    private long sequenceNumber;

    @Column(name = "location", updatable = false, length = 255)
    private String location;

    @Column(name = "description", updatable = false, length = 512)
    private String description;

    @Column(name = "correlation_id", nullable = false, updatable = false, length = 64)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, updatable = false, length = 32)
    private EventProcessingStatus processingStatus;

    /** For JPA only. */
    protected TrackingEvent() {
    }

    private TrackingEvent(Builder builder) {
        this.eventId = Objects.requireNonNull(builder.eventId, "eventId");
        this.shipmentId = Objects.requireNonNull(builder.shipmentId, "shipmentId");
        this.trackingNumber = Objects.requireNonNull(builder.trackingNumber, "trackingNumber");
        this.carrierCode = Objects.requireNonNull(builder.carrierCode, "carrierCode");
        this.carrierEventType = Objects.requireNonNull(builder.carrierEventType, "carrierEventType");
        this.normalizedEventType =
                Objects.requireNonNull(builder.normalizedEventType, "normalizedEventType");
        this.eventTime = Objects.requireNonNull(builder.eventTime, "eventTime");
        this.receivedAt = Objects.requireNonNull(builder.receivedAt, "receivedAt");
        this.sequenceNumber = builder.sequenceNumber;
        this.location = builder.location;
        this.description = builder.description;
        this.correlationId = Objects.requireNonNull(builder.correlationId, "correlationId");
        this.processingStatus =
                Objects.requireNonNull(builder.processingStatus, "processingStatus");
    }

    /**
     * A builder rather than a 13-argument constructor. Six of the fields are strings, four of them
     * optional-looking, and positional arguments of the same type are exactly the shape that lets a
     * caller silently swap {@code location} and {@code description}.
     */
    public static Builder builder() {
        return new Builder();
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getShipmentId() {
        return shipmentId;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public CarrierCode getCarrierCode() {
        return carrierCode;
    }

    public String getCarrierEventType() {
        return carrierEventType;
    }

    public ShipmentStatus getNormalizedEventType() {
        return normalizedEventType;
    }

    public Instant getEventTime() {
        return eventTime;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public String getLocation() {
        return location;
    }

    public String getDescription() {
        return description;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public EventProcessingStatus getProcessingStatus() {
        return processingStatus;
    }

    /** Identity is the carrier's {@code eventId}, which is stable before and after persistence. */
    @Override
    public boolean equals(Object other) {
        return other instanceof TrackingEvent event && Objects.equals(eventId, event.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(eventId);
    }

    @Override
    public String toString() {
        return "TrackingEvent[eventId=%s, shipmentId=%s, carrierCode=%s, carrierEventType=%s, "
                + "normalizedEventType=%s, sequenceNumber=%d, processingStatus=%s]"
                .formatted(eventId, shipmentId, carrierCode, carrierEventType,
                        normalizedEventType, sequenceNumber, processingStatus);
    }

    public static final class Builder {

        private UUID eventId;
        private UUID shipmentId;
        private String trackingNumber;
        private CarrierCode carrierCode;
        private String carrierEventType;
        private ShipmentStatus normalizedEventType;
        private Instant eventTime;
        private Instant receivedAt;
        private long sequenceNumber;
        private String location;
        private String description;
        private String correlationId;
        private EventProcessingStatus processingStatus;

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

        public Builder trackingNumber(String trackingNumber) {
            this.trackingNumber = trackingNumber;
            return this;
        }

        public Builder carrierCode(CarrierCode carrierCode) {
            this.carrierCode = carrierCode;
            return this;
        }

        public Builder carrierEventType(String carrierEventType) {
            this.carrierEventType = carrierEventType;
            return this;
        }

        public Builder normalizedEventType(ShipmentStatus normalizedEventType) {
            this.normalizedEventType = normalizedEventType;
            return this;
        }

        public Builder eventTime(Instant eventTime) {
            this.eventTime = eventTime;
            return this;
        }

        public Builder receivedAt(Instant receivedAt) {
            this.receivedAt = receivedAt;
            return this;
        }

        public Builder sequenceNumber(long sequenceNumber) {
            this.sequenceNumber = sequenceNumber;
            return this;
        }

        public Builder location(String location) {
            this.location = location;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder processingStatus(EventProcessingStatus processingStatus) {
            this.processingStatus = processingStatus;
            return this;
        }

        public TrackingEvent build() {
            return new TrackingEvent(this);
        }
    }
}
