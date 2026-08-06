package ca.vm.parcelflow.notification.api;

import ca.vm.parcelflow.notification.domain.Notification;
import ca.vm.parcelflow.notification.domain.NotificationChannel;
import ca.vm.parcelflow.notification.domain.NotificationStatus;
import ca.vm.parcelflow.notification.domain.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A notification record generated for a delivery milestone. Nothing is sent.")
public record NotificationResponse(
        UUID notificationId,

        @Schema(description = "The tracking event that caused this notification")
        UUID sourceEventId,

        NotificationType notificationType,
        NotificationChannel channel,
        NotificationStatus status,
        Instant createdAt) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getNotificationId(),
                notification.getSourceEventId(),
                notification.getNotificationType(),
                notification.getChannel(),
                notification.getStatus(),
                notification.getCreatedAt());
    }
}
