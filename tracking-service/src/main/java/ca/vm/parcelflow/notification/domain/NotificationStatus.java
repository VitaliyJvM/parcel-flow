package ca.vm.parcelflow.notification.domain;

/**
 * Delivery state of a notification record.
 *
 * <p>Everything ParcelFlow creates is {@code PENDING} and stays there. The dispatcher that would
 * claim these rows and move them to a sent state is explicitly out of scope — the MVP creates
 * records, it does not deliver messages.
 */
public enum NotificationStatus {
    PENDING
}
