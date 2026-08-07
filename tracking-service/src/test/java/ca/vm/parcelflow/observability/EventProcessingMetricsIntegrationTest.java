package ca.vm.parcelflow.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.infrastructure.observability.BacklogMetrics;
import ca.vm.parcelflow.shipment.ShipmentRepository;
import ca.vm.parcelflow.shipment.domain.Shipment;
import ca.vm.parcelflow.support.CarrierEvents;
import ca.vm.parcelflow.support.PostgresIntegrationTest;
import ca.vm.parcelflow.tracking.TrackingEventProcessor;
import ca.vm.parcelflow.tracking.TrackingEventRepository;
import ca.vm.parcelflow.tracking.failure.FailedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Metrics against the real pipeline, and against the real scrape endpoint.
 *
 * <p>Two things are being checked, and they fail for different reasons. That the pipeline
 * increments the right counter is a wiring question — a call site that was never added, or added to
 * the wrong branch. That {@code /actuator/prometheus} exposes the name a dashboard queries is a
 * <em>naming convention</em> question: Micrometer rewrites dots to underscores and appends
 * {@code _total} to counters and {@code _seconds} to timers, so the string a Grafana panel needs is
 * never the string the Java code declares. Asserting the exported text is the only way to know the
 * two agree.
 *
 * <p>Deltas rather than absolute values throughout: the meter registry belongs to a Spring context
 * that the test framework caches and shares with other test classes, so a counter's absolute value
 * depends on what ran before it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventProcessingMetricsIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private TrackingEventProcessor processor;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BacklogMetrics backlogMetrics;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private TrackingEventRepository trackingEventRepository;

    @Autowired
    private FailedEventRepository failedEventRepository;

    private UUID shipmentId;

    @BeforeEach
    void registerShipment() {
        trackingEventRepository.deleteAll();
        failedEventRepository.deleteAll();
        shipmentRepository.deleteAll();
        shipmentId = saveShipment("SP-METRICS-1");
    }

    @Test
    @DisplayName("an applied event moves received, processed, applied and the duration timer")
    void appliedEventMovesTheOutcomeCounters() {
        double received = counter("parcelflow.tracking.events.received");
        double processed = counter("parcelflow.tracking.events.processed");
        double applied = counter("parcelflow.tracking.events.applied");
        long timed = timerCount("applied");

        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_PICKUP", 1).build());

        assertThat(counter("parcelflow.tracking.events.received")).isEqualTo(received + 1);
        assertThat(counter("parcelflow.tracking.events.processed")).isEqualTo(processed + 1);
        assertThat(counter("parcelflow.tracking.events.applied")).isEqualTo(applied + 1);
        assertThat(timerCount("applied")).isEqualTo(timed + 1);
    }

    @Test
    @DisplayName("a redelivered event increments duplicate, and nothing else about the shipment")
    void duplicateEventIsCountedAsADuplicate() {
        var message = CarrierEvents.swiftPost(shipmentId, "SP_PICKUP", 1).build();
        processor.process(message);

        double duplicates = counter("parcelflow.tracking.events.duplicate");
        double applied = counter("parcelflow.tracking.events.applied");

        processor.process(message);

        assertThat(counter("parcelflow.tracking.events.duplicate")).isEqualTo(duplicates + 1);
        assertThat(counter("parcelflow.tracking.events.applied")).isEqualTo(applied);
    }

    @Test
    @DisplayName("an event that arrives too late increments the out-of-order counter")
    void lateEventIsCountedAsOutOfOrder() {
        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_OFD", 5).build());

        double outOfOrder = counter("parcelflow.tracking.events.out.of.order");

        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_TRANSIT", 3).build());

        assertThat(counter("parcelflow.tracking.events.out.of.order")).isEqualTo(outOfOrder + 1);
    }

    @Test
    @DisplayName("a rejected event is counted under the same category the classifier assigns it")
    void failureIsCountedUnderItsCategory() {
        double validationFailures = failuresFor("VALIDATION");

        var invalid = CarrierEvents.swiftPost(shipmentId, "SP_TRANSIT", 1)
                .schemaVersion(99)
                .build();

        assertThatThrownBy(() -> processor.process(invalid)).isInstanceOf(RuntimeException.class);

        assertThat(failuresFor("VALIDATION")).isEqualTo(validationFailures + 1);
    }

    @Test
    @DisplayName("an applied milestone creates a notification and counts it; a non-milestone is skipped")
    void notificationOutcomesAreCounted() {
        double created = registry.get("parcelflow.notifications.created")
                .tags("type", "PICKED_UP").counter().count();
        double skipped = registry.get("parcelflow.notifications.skipped")
                .tags("reason", "not_notifiable").counter().count();

        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_PICKUP", 1).build());
        // IN_TRANSIT is deliberately not a notifiable milestone.
        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_TRANSIT", 2).build());

        assertThat(registry.get("parcelflow.notifications.created")
                .tags("type", "PICKED_UP").counter().count()).isEqualTo(created + 1);
        assertThat(registry.get("parcelflow.notifications.skipped")
                .tags("reason", "not_notifiable").counter().count()).isEqualTo(skipped + 1);
    }

    @Test
    @DisplayName("the backlog gauges report what is actually outstanding")
    void backlogGaugesReflectTheDatabase() {
        saveShipment("SP-METRICS-2");

        backlogMetrics.refresh();

        // Two shipments exist, neither delivered.
        assertThat(gauge("parcelflow.shipments.active")).isEqualTo(2);
        assertThat(gauge("parcelflow.failed.events.awaiting.review")).isZero();
        assertThat(gauge("parcelflow.dead.letters.unresolved")).isZero();

        String[] journey = {"SP_CREATED", "SP_PICKUP", "SP_TRANSIT", "SP_DEPOT", "SP_OFD",
                "SP_DELIVERED"};
        for (int i = 0; i < journey.length; i++) {
            processor.process(CarrierEvents.swiftPost(shipmentId, journey[i], i + 1L).build());
        }

        backlogMetrics.refresh();

        assertThat(gauge("parcelflow.shipments.active")).isEqualTo(1);
    }

    @Test
    @DisplayName("the scrape endpoint exposes the exact metric names dashboards and alerts query")
    void prometheusExposesTheDocumentedNames() throws Exception {
        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_PICKUP", 1).build());
        backlogMetrics.refresh();

        String scrape = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(scrape).contains(
                "parcelflow_tracking_events_received_total",
                "parcelflow_tracking_events_processed_total",
                "parcelflow_tracking_events_applied_total",
                "parcelflow_tracking_events_out_of_order_total",
                "parcelflow_tracking_events_duplicate_total",
                "parcelflow_tracking_events_failed_total",
                "parcelflow_tracking_events_dlt_total",
                "parcelflow_tracking_events_optimistic_lock_conflicts_total",
                "parcelflow_tracking_event_processing_duration_seconds",
                // Not parcelflow_notifications_created_total. `_created` is a reserved
                // OpenMetrics suffix — it marks a series' creation timestamp — so the Prometheus
                // client strips it from the Micrometer name `parcelflow.notifications.created`
                // and the exposition is parcelflow_notifications_total. This assertion exists to
                // pin that surprise down: it is the name every dashboard and alert must use, and
                // it is documented in docs/operations.md.
                "parcelflow_notifications_total",
                "parcelflow_notifications_skipped_total",
                "parcelflow_shipments_active",
                "parcelflow_failed_events_awaiting_review",
                "parcelflow_dead_letters_unresolved");

        // The histogram buckets, without which histogram_quantile() has nothing to work with and
        // every latency panel is empty.
        assertThat(scrape).contains("parcelflow_tracking_event_processing_duration_seconds_bucket");

        // Common tags, which is what lets one dashboard serve more than one deployment.
        assertThat(scrape).contains("application=\"tracking-service\"");

        // The cardinality guarantee, asserted against the actual exposition rather than the Java
        // declarations: a scrape containing a shipment id would mean a series per parcel.
        assertThat(scrape).doesNotContain("shipmentId=", "eventId=", "trackingNumber=",
                "correlationId=", shipmentId.toString());
    }

    private double counter(String name) {
        return Search.in(registry).name(name).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    private double failuresFor(String category) {
        return registry.get("parcelflow.tracking.events.failed")
                .tags("category", category)
                .counter()
                .count();
    }

    private long timerCount(String outcome) {
        return registry.get("parcelflow.tracking.event.processing.duration")
                .tags("outcome", outcome)
                .timer()
                .count();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private UUID saveShipment(String trackingNumber) {
        return shipmentRepository.saveAndFlush(Shipment.register(
                        UUID.randomUUID(),
                        "retailer-metrics",
                        "cust-metrics",
                        trackingNumber,
                        CarrierCode.SWIFTPOST,
                        LocalDate.parse("2026-08-12"),
                        CarrierEvents.T0))
                .getShipmentId();
    }
}
