package ca.vm.parcelflow.notification;

import ca.vm.parcelflow.notification.domain.Notification;
import ca.vm.parcelflow.notification.domain.NotificationType;
import ca.vm.parcelflow.shipment.ShipmentService;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates and reads notification records. Nothing here sends anything.
 *
 * <p>{@link #recordMilestone} is called from inside the tracking event transaction, so a
 * notification and the event that justifies it commit together or not at all. A rollback cannot
 * leave a customer notified about an event that was never stored.
 */
@Service
public class NotificationService {

    /**
     * Stable ordering for the history endpoint. {@code notificationId} is the final key because
     * notifications created in one transaction share {@code createdAt} to the microsecond, and
     * without a tie-break those rows would page non-deterministically.
     */
    private static final Sort CHRONOLOGICAL =
            Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("notificationId"));

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final ShipmentService shipmentService;
    private final NotificationMetrics metrics;

    public NotificationService(
            NotificationRepository notificationRepository,
            ShipmentService shipmentService,
            NotificationMetrics metrics) {
        this.notificationRepository = notificationRepository;
        this.shipmentService = shipmentService;
        this.metrics = metrics;
    }

    /**
     * Creates a notification record if the status is a notifiable milestone.
     *
     * <p>Must only be called for events that were <em>applied</em>. A superseded event describes a
     * milestone the parcel passed long ago, and notifying a customer that their delivered parcel is
     * now in transit is worse than saying nothing.
     *
     * @return the created notification, or empty if the status is not notifiable or a notification
     *     for this event already exists
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Optional<Notification> recordMilestone(
            UUID shipmentId, UUID sourceEventId, ShipmentStatus status, Instant now) {

        Optional<NotificationType> type = NotificationType.forStatus(status);
        if (type.isEmpty()) {
            metrics.notificationSkipped(NotificationMetrics.SkipReason.NOT_NOTIFIABLE);
            return Optional.empty();
        }

        // Cheap guard for the common case. The real guarantee is the unique constraint on
        // (shipment_id, source_event_id): two consumer threads racing on a redelivered event both
        // pass this check, and the constraint is what stops the second insert.
        if (notificationRepository.existsByShipmentIdAndSourceEventId(shipmentId, sourceEventId)) {
            metrics.notificationSkipped(NotificationMetrics.SkipReason.ALREADY_EXISTS);
            log.debug("Notification already exists for shipment {} event {}", shipmentId, sourceEventId);
            return Optional.empty();
        }

        Notification notification =
                notificationRepository.save(Notification.forEvent(shipmentId, sourceEventId, type.get(), now));

        // Counted before the transaction commits, so a rolled-back transaction leaves the counter
        // one ahead of the table. Deliberate: the alternative is an after-commit hook that would
        // couple the rule engine to transaction synchronization for a counter, and the rollback
        // case is itself counted as a processing failure.
        metrics.notificationCreated(type.get());

        log.info("Created {} notification for shipment {} from event {}",
                type.get(), shipmentId, sourceEventId);
        return Optional.of(notification);
    }

    /**
     * @throws ca.vm.parcelflow.shipment.ShipmentNotFoundException if the shipment does not exist,
     *     so an unknown parcel is a 404 rather than an empty page
     */
    @Transactional(readOnly = true)
    public Page<Notification> findShipmentNotifications(UUID shipmentId, int page, int size) {
        shipmentService.requireShipmentExists(shipmentId);
        return notificationRepository.findByShipmentId(
                shipmentId, PageRequest.of(page, size, CHRONOLOGICAL));
    }
}
