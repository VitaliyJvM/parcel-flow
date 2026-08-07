package ca.vm.parcelflow.infrastructure.observability;

import ca.vm.parcelflow.shipment.ShipmentRepository;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import ca.vm.parcelflow.tracking.failure.FailedEventRepository;
import ca.vm.parcelflow.tracking.failure.FailedEventStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three "how much work is outstanding" numbers, as gauges.
 *
 * <p>Counters answer "how fast"; these answer "how deep". A failure rate of zero looks healthy even
 * when four hundred events are sitting in {@code failed_events} waiting for somebody to look at
 * them, because they failed yesterday. Backlog is the metric that does not heal itself when you
 * stop watching.
 *
 * <p><b>Refreshed on a schedule, not on scrape.</b> A gauge whose supplier runs a {@code COUNT}
 * against PostgreSQL executes on the scrape thread — so the cost scales with the number of scrapers,
 * and a slow or unavailable database turns a metrics scrape into a hanging HTTP request or a broken
 * one. Polling on a fixed interval into an {@link AtomicLong} makes the database load constant and
 * independent of who is scraping, and means a database outage leaves the last known values in place
 * (visibly stale, with {@code db} already DOWN in the health endpoint to explain why) rather than
 * blanking the panel.
 *
 * <p>The queries are three unfiltered counts against indexed status columns. They are cheap at this
 * scale and would need to become materialized counters at a scale this project does not claim to
 * have reached.
 */
@Component
public class BacklogMetrics {

    private static final Logger log = LoggerFactory.getLogger(BacklogMetrics.class);

    private final ShipmentRepository shipmentRepository;
    private final FailedEventRepository failedEventRepository;

    private final AtomicLong activeShipments = new AtomicLong();
    private final AtomicLong failedEventsAwaitingReview = new AtomicLong();
    private final AtomicLong unresolvedDeadLetters = new AtomicLong();

    public BacklogMetrics(
            ShipmentRepository shipmentRepository,
            FailedEventRepository failedEventRepository,
            MeterRegistry registry) {

        this.shipmentRepository = shipmentRepository;
        this.failedEventRepository = failedEventRepository;

        Gauge.builder("parcelflow.shipments.active", activeShipments, AtomicLong::get)
                .description("Shipments that have not reached a terminal status")
                .register(registry);

        Gauge.builder("parcelflow.failed.events.awaiting.review", failedEventsAwaitingReview,
                        AtomicLong::get)
                .description("Failed events in FAILED status, waiting for an operator decision")
                .register(registry);

        Gauge.builder("parcelflow.dead.letters.unresolved", unresolvedDeadLetters, AtomicLong::get)
                .description("Failed-event records not yet resolved, in any status. Every "
                        + "dead-lettered message has one of these rows, so this is the durable "
                        + "count of unresolved dead letters")
                .register(registry);
    }

    /**
     * Refreshes all three gauges.
     *
     * <p>Runs once at startup as well as on the interval, so a freshly restarted instance reports
     * the real backlog immediately instead of three zeros — zeros that would otherwise resolve an
     * open alert for as long as the refresh interval.
     */
    @Scheduled(
            initialDelayString = "${parcelflow.metrics.backlog-refresh-initial-delay-ms:0}",
            fixedRateString = "${parcelflow.metrics.backlog-refresh-interval-ms:15000}")
    @Transactional(readOnly = true)
    public void refresh() {
        try {
            activeShipments.set(shipmentRepository.countByCurrentStatusNot(ShipmentStatus.DELIVERED));
            failedEventsAwaitingReview.set(
                    failedEventRepository.countByStatus(FailedEventStatus.FAILED));
            unresolvedDeadLetters.set(
                    failedEventRepository.countByStatusNot(FailedEventStatus.RESOLVED));
        } catch (RuntimeException e) {
            // Never propagate: an exception out of a scheduled method is logged by the scheduler
            // and the task keeps running, but the noise is unhelpful and the condition is already
            // reported by the db health contributor. The gauges keep their last known values.
            log.warn("Could not refresh backlog gauges; the reported values are stale: {}",
                    e.getMessage());
        }
    }
}
