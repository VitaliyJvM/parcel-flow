package ca.vm.parcelflow.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ca.vm.parcelflow.tracking.TrackingEventMetrics;
import ca.vm.parcelflow.tracking.error.ErrorCategory;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The meter contract, tested without Spring.
 *
 * <p>Metric names are an interface. A dashboard panel, an alert rule and a runbook all name them as
 * strings, and none of those break at compile time — so a rename that looks like a harmless tidy-up
 * silently blanks a panel and disarms an alert. These assertions spell the names out so that the
 * rename has to be deliberate, and so the person doing it is told exactly which dashboards to fix.
 *
 * <p>The registered-but-never-incremented assertion matters as much as the counting one: a
 * {@code rate()} over a series that does not exist yet returns no data, which on a dashboard is
 * indistinguishable from a broken query.
 */
class TrackingEventMetricsTest {

    private MeterRegistry registry;
    private TrackingEventMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new TrackingEventMetrics(registry);
    }

    @Test
    @DisplayName("every documented meter is registered before anything is processed")
    void registersAllMetersUpFront() {
        assertThat(meterNames()).contains(
                "parcelflow.tracking.events.received",
                "parcelflow.tracking.events.processed",
                "parcelflow.tracking.events.applied",
                "parcelflow.tracking.events.out.of.order",
                "parcelflow.tracking.events.duplicate",
                "parcelflow.tracking.events.failed",
                "parcelflow.tracking.events.dlt",
                "parcelflow.tracking.events.optimistic.lock.conflicts",
                "parcelflow.tracking.event.processing.duration");
    }

    @Test
    @DisplayName("a failure series exists for every error category, before the first failure")
    void registersAFailureSeriesPerCategory() {
        for (ErrorCategory category : ErrorCategory.values()) {
            assertThat(registry.find("parcelflow.tracking.events.failed")
                    .tags("category", category.name(),
                            "retryable", String.valueOf(category.isRetryableAutomatically()))
                    .counter())
                    .as("failure counter for %s", category)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("an applied event counts as received, processed and applied, and is timed")
    void countsAnAppliedEvent() {
        metrics.recordApplied(metrics.startProcessing());

        assertThat(counter("parcelflow.tracking.events.received")).isEqualTo(1);
        assertThat(counter("parcelflow.tracking.events.processed")).isEqualTo(1);
        assertThat(counter("parcelflow.tracking.events.applied")).isEqualTo(1);
        assertThat(counter("parcelflow.tracking.events.duplicate")).isZero();
        assertThat(counter("parcelflow.tracking.events.out.of.order")).isZero();
        assertThat(timerCount("applied")).isEqualTo(1);
    }

    @Test
    @DisplayName("a duplicate is a processed event, not a failure")
    void countsADuplicateAsProcessed() {
        metrics.recordDuplicate(metrics.startProcessing());

        assertThat(counter("parcelflow.tracking.events.duplicate")).isEqualTo(1);
        assertThat(counter("parcelflow.tracking.events.processed")).isEqualTo(1);
        assertThat(failedTotal()).isZero();
        assertThat(timerCount("duplicate")).isEqualTo(1);
    }

    @Test
    @DisplayName("an out-of-order event is counted separately from an applied one")
    void countsAnOutOfOrderEvent() {
        metrics.recordOutOfOrder(metrics.startProcessing());

        assertThat(counter("parcelflow.tracking.events.out.of.order")).isEqualTo(1);
        assertThat(counter("parcelflow.tracking.events.processed")).isEqualTo(1);
        assertThat(counter("parcelflow.tracking.events.applied")).isZero();
        assertThat(timerCount("out_of_order")).isEqualTo(1);
    }

    @Test
    @DisplayName("a failure is counted under its category and is not counted as processed")
    void countsAFailure() {
        metrics.recordFailure(metrics.startProcessing(), ErrorCategory.VALIDATION);

        assertThat(counter("parcelflow.tracking.events.received")).isEqualTo(1);
        assertThat(counter("parcelflow.tracking.events.processed")).isZero();
        assertThat(registry.get("parcelflow.tracking.events.failed")
                .tags("category", "VALIDATION").counter().count()).isEqualTo(1);
        assertThat(timerCount("failed")).isEqualTo(1);
    }

    @Test
    @DisplayName("processed is the sum of applied, out of order and duplicate")
    void processedPartitionsTheSuccessfulOutcomes() {
        metrics.recordApplied(metrics.startProcessing());
        metrics.recordApplied(metrics.startProcessing());
        metrics.recordOutOfOrder(metrics.startProcessing());
        metrics.recordDuplicate(metrics.startProcessing());
        metrics.recordFailure(metrics.startProcessing(), ErrorCategory.INFRASTRUCTURE);

        assertThat(counter("parcelflow.tracking.events.received")).isEqualTo(5);
        assertThat(counter("parcelflow.tracking.events.processed")).isEqualTo(4);
        assertThat(counter("parcelflow.tracking.events.applied")
                + counter("parcelflow.tracking.events.out.of.order")
                + counter("parcelflow.tracking.events.duplicate"))
                .isEqualTo(counter("parcelflow.tracking.events.processed"));
    }

    @Test
    @DisplayName("dead letters and lock conflicts are counted independently of the outcome path")
    void countsDeadLettersAndLockConflicts() {
        metrics.eventDeadLettered();
        metrics.optimisticLockConflict();
        metrics.optimisticLockConflict();
        metrics.eventFailed(ErrorCategory.MALFORMED_PAYLOAD);

        assertThat(counter("parcelflow.tracking.events.dlt")).isEqualTo(1);
        assertThat(counter("parcelflow.tracking.events.optimistic.lock.conflicts")).isEqualTo(2);
        assertThat(registry.get("parcelflow.tracking.events.failed")
                .tags("category", "MALFORMED_PAYLOAD").counter().count()).isEqualTo(1);
        // No timer sample: a record that never deserialized has no processing duration.
        assertThat(timerCount("failed")).isZero();
    }

    @Test
    @DisplayName("no meter is tagged with an unbounded identifier")
    void carriesNoHighCardinalityTags() {
        metrics.recordApplied(metrics.startProcessing());

        List<String> forbidden = List.of("shipmentId", "eventId", "trackingNumber", "correlationId",
                "shipment_id", "event_id", "tracking_number", "correlation_id");

        for (Meter meter : registry.getMeters()) {
            assertThat(meter.getId().getTags().stream().map(io.micrometer.core.instrument.Tag::getKey))
                    .as("tags on %s", meter.getId().getName())
                    .doesNotContainAnyElementsOf(forbidden);
        }
    }

    private List<String> meterNames() {
        return registry.getMeters().stream().map(meter -> meter.getId().getName()).toList();
    }

    /** Summed across categories: the failure counter is one series per error category. */
    private double failedTotal() {
        return registry.find("parcelflow.tracking.events.failed").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    private long timerCount(String outcome) {
        return registry.get("parcelflow.tracking.event.processing.duration")
                .tags(Tags.of("outcome", outcome))
                .timer()
                .count();
    }
}
