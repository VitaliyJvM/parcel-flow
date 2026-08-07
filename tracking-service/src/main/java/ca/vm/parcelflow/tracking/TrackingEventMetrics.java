package ca.vm.parcelflow.tracking;

import ca.vm.parcelflow.tracking.error.ErrorCategory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Every meter the event pipeline publishes.
 *
 * <p>Counters and timers are resolved once in the constructor rather than looked up per increment:
 * Micrometer meter lookup takes a lock on the registry's meter map, which is not what you want on
 * the hot path of a consumer.
 *
 * <p><b>No identifier is ever a tag.</b> Not {@code shipmentId}, not {@code eventId}, not
 * {@code trackingNumber}, not {@code correlationId}. Each of those is unbounded, and a tag with
 * unbounded values creates one time series per value — which is how a Prometheus server runs out of
 * memory. Identifiers belong in logs, where they can be searched; metrics carry only dimensions
 * with a small fixed set of values ({@link ErrorCategory}, the processing outcome).
 *
 * <p><b>Counting model.</b> Three outcome counters partition every successful processing attempt:
 *
 * <pre>
 *   processed = applied + out.of.order + duplicate
 * </pre>
 *
 * <p>{@code received} is incremented once per entry into the pipeline, which includes Kafka
 * redeliveries and operator-driven manual retries — both are genuinely another unit of work. A
 * record that fails <em>deserialization</em> never reaches the processor, so it is counted by
 * {@code failed} and {@code dlt} but not by {@code received}; that gap is the deserialization
 * failure count, and is deliberate rather than an oversight.
 *
 * @see ca.vm.parcelflow.infrastructure.observability.BacklogMetrics for the backlog gauges
 */
@Component
public class TrackingEventMetrics {

    private static final String EVENTS = "parcelflow.tracking.events";
    private static final String DURATION = "parcelflow.tracking.event.processing.duration";

    private final MeterRegistry registry;

    private final Counter received;
    private final Counter processed;
    private final Counter applied;
    private final Counter outOfOrder;
    private final Counter duplicate;
    private final Counter optimisticLockConflicts;
    private final Counter deadLettered;
    private final Map<ErrorCategory, Counter> failuresByCategory = new EnumMap<>(ErrorCategory.class);
    private final Map<Outcome, Timer> processingTimers = new EnumMap<>(Outcome.class);

    /** The tag value on {@code parcelflow.tracking.event.processing.duration}. */
    public enum Outcome {
        APPLIED,
        OUT_OF_ORDER,
        DUPLICATE,
        FAILED;

        String tagValue() {
            return name().toLowerCase();
        }
    }

    public TrackingEventMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.received = Counter.builder(EVENTS + ".received")
                .description("Carrier events that entered the processing pipeline, including "
                        + "redeliveries and manual retries")
                .register(registry);
        this.processed = Counter.builder(EVENTS + ".processed")
                .description("Carrier events processed without error: applied + out of order + "
                        + "duplicate")
                .register(registry);
        this.applied = Counter.builder(EVENTS + ".applied")
                .description("Events that advanced the shipment's current status")
                .register(registry);
        this.outOfOrder = Counter.builder(EVENTS + ".out.of.order")
                .description("Events stored in history but too late to change the current status")
                .register(registry);
        this.duplicate = Counter.builder(EVENTS + ".duplicate")
                .description("Redeliveries of an event already stored")
                .register(registry);
        this.optimisticLockConflicts = Counter.builder(EVENTS + ".optimistic.lock.conflicts")
                .description("Shipment update conflicts that triggered an in-process retry")
                .register(registry);
        this.deadLettered = Counter.builder(EVENTS + ".dlt")
                .description("Events published to the dead letter topic")
                .register(registry);

        // Pre-registered for every category so a dashboard panel and an alert rule have a series
        // to read from before the first failure of that kind ever happens. A rate() over a metric
        // that does not exist yet returns no data, which looks the same as a broken query.
        for (ErrorCategory category : ErrorCategory.values()) {
            failuresByCategory.put(category, Counter.builder(EVENTS + ".failed")
                    .description("Event processing attempts that failed, by error category")
                    .tag("category", category.name())
                    .tag("retryable", String.valueOf(category.isRetryableAutomatically()))
                    .register(registry));
        }

        for (Outcome outcome : Outcome.values()) {
            processingTimers.put(outcome, Timer.builder(DURATION)
                    .description("Wall-clock time to process one carrier event end to end")
                    .tag("outcome", outcome.tagValue())
                    // A histogram, not client-side percentiles: percentiles computed per instance
                    // cannot be aggregated across instances, and the whole point of a p99 is to
                    // read it for the service rather than for one JVM.
                    .publishPercentileHistogram()
                    .minimumExpectedValue(Duration.ofMillis(1))
                    .maximumExpectedValue(Duration.ofSeconds(10))
                    .register(registry));
        }
    }

    /** Starts the processing timer. The sample is stopped by one of the {@code record*} methods. */
    public Timer.Sample startProcessing() {
        received.increment();
        return Timer.start(registry);
    }

    public void recordApplied(Timer.Sample sample) {
        processed.increment();
        applied.increment();
        sample.stop(processingTimers.get(Outcome.APPLIED));
    }

    public void recordOutOfOrder(Timer.Sample sample) {
        processed.increment();
        outOfOrder.increment();
        sample.stop(processingTimers.get(Outcome.OUT_OF_ORDER));
    }

    public void recordDuplicate(Timer.Sample sample) {
        processed.increment();
        duplicate.increment();
        sample.stop(processingTimers.get(Outcome.DUPLICATE));
    }

    public void recordFailure(Timer.Sample sample, ErrorCategory category) {
        eventFailed(category);
        sample.stop(processingTimers.get(Outcome.FAILED));
    }

    /**
     * Counts a failure with no timer attached — a record that failed deserialization and therefore
     * never entered the pipeline, so there is no processing duration to report.
     */
    public void eventFailed(ErrorCategory category) {
        failuresByCategory.get(category).increment();
    }

    public void optimisticLockConflict() {
        optimisticLockConflicts.increment();
    }

    public void eventDeadLettered() {
        deadLettered.increment();
    }
}
