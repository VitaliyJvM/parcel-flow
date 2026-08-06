package ca.vm.parcelflow.notification.domain;

/**
 * Where a notification would be delivered.
 *
 * <p>One value, because ParcelFlow does not dispatch anything. Choosing a channel means reading
 * customer contact preferences, which would pull personal data into a service that currently holds
 * only an opaque customer reference — a deliberate boundary. The column exists so the record is
 * shaped like the real thing.
 */
public enum NotificationChannel {
    EMAIL
}
