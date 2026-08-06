package ca.vm.parcelflow.notification.domain;

import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * The customer-facing milestones ParcelFlow notifies about.
 *
 * <p>A separate enum from {@link ShipmentStatus} rather than an {@code isNotifiable()} flag on the
 * status. Which milestones deserve a message is a product decision that will change — and changing
 * it should not mean editing the shipment aggregate, which has nothing to do with customer
 * messaging. The five values happen to correspond to five statuses today; three statuses
 * deliberately have no notification, because "arrived at facility" for the eleventh time is noise.
 */
public enum NotificationType {

    PICKED_UP,
    OUT_FOR_DELIVERY,
    DELAYED,
    DELIVERY_ATTEMPTED,
    DELIVERED;

    private static final Map<ShipmentStatus, NotificationType> BY_STATUS =
            new EnumMap<>(ShipmentStatus.class);

    static {
        BY_STATUS.put(ShipmentStatus.PICKED_UP, PICKED_UP);
        BY_STATUS.put(ShipmentStatus.OUT_FOR_DELIVERY, OUT_FOR_DELIVERY);
        BY_STATUS.put(ShipmentStatus.DELAYED, DELAYED);
        BY_STATUS.put(ShipmentStatus.DELIVERY_ATTEMPTED, DELIVERY_ATTEMPTED);
        BY_STATUS.put(ShipmentStatus.DELIVERED, DELIVERED);
        // Not notified: LABEL_CREATED (the retailer already knows), IN_TRANSIT and
        // ARRIVED_AT_FACILITY (both repeat many times per journey).
    }

    /**
     * The deterministic mapping from a normalized status to a notification.
     *
     * @return empty when the status is not a notifiable milestone
     */
    public static Optional<NotificationType> forStatus(ShipmentStatus status) {
        return Optional.ofNullable(BY_STATUS.get(status));
    }
}
