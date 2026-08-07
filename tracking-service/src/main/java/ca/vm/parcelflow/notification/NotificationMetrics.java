package ca.vm.parcelflow.notification;

import ca.vm.parcelflow.notification.domain.NotificationType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Counters for the notification rule engine.
 *
 * <p>Created and skipped are both worth counting, and skipped needs a reason. A milestone that
 * should have produced a message and did not is a customer-visible bug, and the two reasons have
 * opposite implications: {@code NOT_NOTIFIABLE} is the rule engine working as designed and should
 * be the overwhelming majority, while {@code ALREADY_EXISTS} means an event was reprocessed — which
 * is fine, but a sudden climb in it is the shape of a redelivery storm.
 *
 * <p>Both tags are bounded: five notification types, two skip reasons.
 */
@Component
public class NotificationMetrics {

    private final Map<NotificationType, Counter> created = new EnumMap<>(NotificationType.class);
    private final Map<SkipReason, Counter> skipped = new EnumMap<>(SkipReason.class);

    /** Why a milestone did not produce a notification record. */
    public enum SkipReason {
        /** The normalized status is not a customer-facing milestone. */
        NOT_NOTIFIABLE,
        /** A notification for this shipment and source event already exists. */
        ALREADY_EXISTS;

        String tagValue() {
            return name().toLowerCase();
        }
    }

    public NotificationMetrics(MeterRegistry registry) {
        for (NotificationType type : NotificationType.values()) {
            created.put(type, Counter.builder("parcelflow.notifications.created")
                    .description("Notification records created, by milestone")
                    .tag("type", type.name())
                    .register(registry));
        }
        for (SkipReason reason : SkipReason.values()) {
            skipped.put(reason, Counter.builder("parcelflow.notifications.skipped")
                    .description("Applied events that produced no notification record")
                    .tag("reason", reason.tagValue())
                    .register(registry));
        }
    }

    public void notificationCreated(NotificationType type) {
        created.get(type).increment();
    }

    public void notificationSkipped(SkipReason reason) {
        skipped.get(reason).increment();
    }
}
