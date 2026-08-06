package ca.vm.parcelflow.tracking;

import ca.vm.parcelflow.tracking.error.ErrorCategory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Counters for event processing outcomes.
 *
 * <p>The measurement hook, not the observability story. Stage 4 owns dashboards, latency timers and
 * structured logging; what exists here is the set of counters that Stage 4 will expose, placed now
 * because the call sites — a duplicate detected, a lock conflict retried — are only visible from
 * inside the code that handles them.
 *
 * <p>Counters are resolved once in the constructor rather than looked up per increment: Micrometer
 * meter lookup takes a lock on the registry's meter map, which is not what you want on the hot path
 * of a consumer.
 */
@Component
public class TrackingEventMetrics {

    private static final String PREFIX = "parcelflow.tracking.events";

    private final Counter received;
    private final Counter applied;
    private final Counter superseded;
    private final Counter duplicates;
    private final Counter optimisticLockConflicts;
    private final Map<ErrorCategory, Counter> failuresByCategory = new EnumMap<>(ErrorCategory.class);
    private final Counter deadLettered;

    public TrackingEventMetrics(MeterRegistry registry) {
        this.received = Counter.builder(PREFIX + ".received")
                .description("Carrier events taken off the topic")
                .register(registry);
        this.applied = Counter.builder(PREFIX + ".applied")
                .description("Events that advanced the shipment's current status")
                .register(registry);
        this.superseded = Counter.builder(PREFIX + ".superseded")
                .description("Events stored in history but too old to change the current status")
                .register(registry);
        this.duplicates = Counter.builder(PREFIX + ".duplicates")
                .description("Redeliveries of an event already stored")
                .register(registry);
        this.optimisticLockConflicts = Counter.builder(PREFIX + ".optimistic.lock.conflicts")
                .description("Shipment update conflicts that triggered a retry")
                .register(registry);
        this.deadLettered = Counter.builder(PREFIX + ".dead.lettered")
                .description("Events published to the dead letter topic")
                .register(registry);

        for (ErrorCategory category : ErrorCategory.values()) {
            failuresByCategory.put(category, Counter.builder(PREFIX + ".failures")
                    .description("Event processing failures by error category")
                    .tag("category", category.name())
                    .tag("retryable", String.valueOf(category.isRetryableAutomatically()))
                    .register(registry));
        }
    }

    public void eventReceived() {
        received.increment();
    }

    public void eventApplied() {
        applied.increment();
    }

    public void eventSuperseded() {
        superseded.increment();
    }

    public void duplicateEvent() {
        duplicates.increment();
    }

    public void optimisticLockConflict() {
        optimisticLockConflicts.increment();
    }

    public void processingFailed(ErrorCategory category) {
        failuresByCategory.get(category).increment();
    }

    public void eventDeadLettered() {
        deadLettered.increment();
    }
}
