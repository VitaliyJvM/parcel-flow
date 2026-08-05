package ca.vm.parcelflow.shipment.domain;

import ca.vm.parcelflow.carrier.CarrierCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A parcel being tracked, holding the current materialized state of everything the carrier has
 * reported so far.
 *
 * <p>The shipment row is the contended resource in this system: many carrier events for the same
 * parcel can be processed concurrently by different consumer threads. Two mechanisms protect it:
 *
 * <ul>
 *   <li>{@link #recordEvent} decides whether an event is newer than what has already been applied,
 *       so a late-arriving older event never rewinds the status.
 *   <li>The {@link Version} field turns a lost update into an {@code OptimisticLockingFailureException}
 *       instead of silent data loss, so the caller can retry the whole read-decide-write cycle.
 * </ul>
 *
 * <p>Timestamps are passed in rather than read from {@code Instant.now()} so that ordering
 * behaviour can be tested deterministically.
 */
@Entity
@Table(name = "shipments")
public class Shipment {

    @Id
    @Column(name = "shipment_id", nullable = false, updatable = false)
    private UUID shipmentId;

    @Column(name = "retailer_id", nullable = false, updatable = false, length = 64)
    private String retailerId;

    @Column(name = "customer_id", nullable = false, updatable = false, length = 64)
    private String customerId;

    @Column(name = "tracking_number", nullable = false, updatable = false, length = 128)
    private String trackingNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "carrier_code", nullable = false, updatable = false, length = 32)
    private CarrierCode carrierCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_status", nullable = false, length = 32)
    private ShipmentStatus currentStatus;

    @Column(name = "estimated_delivery_date")
    private LocalDate estimatedDeliveryDate;

    /** Event time of the most recent event that was actually applied to this shipment. */
    @Column(name = "last_event_time")
    private Instant lastEventTime;

    /**
     * Sequence number of the most recent event that was actually applied.
     *
     * <p>Not in the original data sketch, but comparing sequence numbers requires remembering the
     * last one applied. Kept on the shipment rather than derived from the event table so the
     * ordering decision is a single row read.
     */
    @Column(name = "last_sequence_number")
    private Long lastSequenceNumber;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** For JPA only. */
    protected Shipment() {
    }

    private Shipment(
            UUID shipmentId,
            String retailerId,
            String customerId,
            String trackingNumber,
            CarrierCode carrierCode,
            ShipmentStatus currentStatus,
            LocalDate estimatedDeliveryDate,
            Instant createdAt) {
        this.shipmentId = Objects.requireNonNull(shipmentId, "shipmentId");
        this.retailerId = Objects.requireNonNull(retailerId, "retailerId");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.trackingNumber = Objects.requireNonNull(trackingNumber, "trackingNumber");
        this.carrierCode = Objects.requireNonNull(carrierCode, "carrierCode");
        this.currentStatus = Objects.requireNonNull(currentStatus, "currentStatus");
        this.estimatedDeliveryDate = estimatedDeliveryDate;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = createdAt;
    }

    /**
     * Registers a new parcel. A shipment starts at {@link ShipmentStatus#LABEL_CREATED} with no
     * applied event, so the very first carrier event always wins.
     */
    public static Shipment register(
            UUID shipmentId,
            String retailerId,
            String customerId,
            String trackingNumber,
            CarrierCode carrierCode,
            LocalDate estimatedDeliveryDate,
            Instant createdAt) {
        return new Shipment(
                shipmentId,
                retailerId,
                customerId,
                trackingNumber,
                carrierCode,
                ShipmentStatus.LABEL_CREATED,
                estimatedDeliveryDate,
                createdAt);
    }

    /**
     * Applies a carrier event to the current shipment state.
     *
     * @return {@code true} if the shipment state advanced, {@code false} if the event was older
     *     than or equal to what has already been applied, or the shipment is already terminal. A
     *     {@code false} result is not an error: the event is still recorded in tracking history,
     *     it just does not change the current status.
     */
    public boolean recordEvent(
            ShipmentStatus status, Instant eventTime, long sequenceNumber, Instant now) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(eventTime, "eventTime");
        Objects.requireNonNull(now, "now");

        if (currentStatus.isTerminal()) {
            return false;
        }
        if (!isNewerThanApplied(eventTime, sequenceNumber)) {
            return false;
        }

        this.currentStatus = status;
        this.lastEventTime = eventTime;
        this.lastSequenceNumber = sequenceNumber;
        this.updatedAt = now;
        return true;
    }

    /**
     * Ordering rule: trust the carrier's per-shipment {@code sequenceNumber} when we have one to
     * compare against, and fall back to {@code eventTime}.
     *
     * <p>Sequence numbers are preferred because they are integers assigned by the carrier in the
     * order it observed the parcel, whereas event times come from scanners whose clocks drift and
     * which sometimes report the same second for consecutive scans. Event time breaks ties when
     * two events share a sequence number.
     */
    private boolean isNewerThanApplied(Instant eventTime, long sequenceNumber) {
        if (lastEventTime == null) {
            return true;
        }
        if (lastSequenceNumber != null && sequenceNumber != lastSequenceNumber) {
            return sequenceNumber > lastSequenceNumber;
        }
        return eventTime.isAfter(lastEventTime);
    }

    /** Replaces the carrier's delivery estimate. */
    public void reviseEstimatedDeliveryDate(LocalDate estimatedDeliveryDate, Instant now) {
        this.estimatedDeliveryDate = estimatedDeliveryDate;
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public UUID getShipmentId() {
        return shipmentId;
    }

    public String getRetailerId() {
        return retailerId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public CarrierCode getCarrierCode() {
        return carrierCode;
    }

    public ShipmentStatus getCurrentStatus() {
        return currentStatus;
    }

    public LocalDate getEstimatedDeliveryDate() {
        return estimatedDeliveryDate;
    }

    public Instant getLastEventTime() {
        return lastEventTime;
    }

    public Long getLastSequenceNumber() {
        return lastSequenceNumber;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Identity is the assigned {@code shipmentId}, which exists before the row is persisted, so
     * equality is stable across the transient/managed boundary.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof Shipment shipment && Objects.equals(shipmentId, shipment.shipmentId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(shipmentId);
    }

    /** Excludes {@code customerId}: it must never reach a log line. */
    @Override
    public String toString() {
        return "Shipment[shipmentId=%s, retailerId=%s, carrierCode=%s, currentStatus=%s, version=%d]"
                .formatted(shipmentId, retailerId, carrierCode, currentStatus, version);
    }
}
