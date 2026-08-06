package ca.vm.parcelflow.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A milestone worth telling a customer about.
 *
 * <p>Carries the id of the tracking event that caused it. That link is what makes duplicate
 * prevention expressible as a database constraint — {@code UNIQUE (shipment_id, source_event_id)} —
 * rather than a check the application has to remember to perform.
 *
 * <p>Immutable, like the event that produced it.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @Column(name = "notification_id", nullable = false, updatable = false)
    private UUID notificationId;

    @Column(name = "shipment_id", nullable = false, updatable = false)
    private UUID shipmentId;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, updatable = false, length = 32)
    private NotificationType notificationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false, length = 16)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private NotificationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** For JPA only. */
    protected Notification() {
    }

    private Notification(
            UUID notificationId,
            UUID shipmentId,
            UUID sourceEventId,
            NotificationType notificationType,
            Instant createdAt) {
        this.notificationId = Objects.requireNonNull(notificationId, "notificationId");
        this.shipmentId = Objects.requireNonNull(shipmentId, "shipmentId");
        this.sourceEventId = Objects.requireNonNull(sourceEventId, "sourceEventId");
        this.notificationType = Objects.requireNonNull(notificationType, "notificationType");
        this.channel = NotificationChannel.EMAIL;
        this.status = NotificationStatus.PENDING;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static Notification forEvent(
            UUID shipmentId, UUID sourceEventId, NotificationType type, Instant createdAt) {
        return new Notification(UUID.randomUUID(), shipmentId, sourceEventId, type, createdAt);
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public UUID getShipmentId() {
        return shipmentId;
    }

    public UUID getSourceEventId() {
        return sourceEventId;
    }

    public NotificationType getNotificationType() {
        return notificationType;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Notification notification
                && Objects.equals(notificationId, notification.notificationId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(notificationId);
    }

    @Override
    public String toString() {
        return "Notification[notificationId=%s, shipmentId=%s, sourceEventId=%s, type=%s, status=%s]"
                .formatted(notificationId, shipmentId, sourceEventId, notificationType, status);
    }
}
