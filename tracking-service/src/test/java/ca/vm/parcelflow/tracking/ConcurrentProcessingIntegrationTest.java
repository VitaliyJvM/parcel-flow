package ca.vm.parcelflow.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.notification.NotificationRepository;
import ca.vm.parcelflow.shipment.ShipmentRepository;
import ca.vm.parcelflow.shipment.domain.Shipment;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import ca.vm.parcelflow.support.CarrierEvents;
import ca.vm.parcelflow.support.PostgresIntegrationTest;
import ca.vm.parcelflow.tracking.domain.EventProcessingStatus;
import ca.vm.parcelflow.tracking.messaging.CarrierTrackingEventMessage;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Optimistic locking under genuine contention.
 *
 * <p>This has to drive {@link TrackingEventProcessor} directly from several threads, because Kafka
 * cannot produce this situation for a single parcel: every event for one shipment carries the same
 * partition key, so the broker delivers them to one consumer thread in order. Partitioning exists
 * precisely to prevent concurrent updates to one aggregate — which is why the simulator's
 * RAPID_CONCURRENT_EVENTS scenario cannot exercise this path and this test can.
 *
 * <p>The real situation this models is two <em>instances</em> of the service after a rebalance,
 * where one still holds a partition the other has been assigned.
 *
 * <p>Synchronisation is a {@link CyclicBarrier}, not a sleep. Every thread blocks until all of them
 * are ready and then they are released together, which makes the overlap real rather than hoped
 * for, and makes the test finish as fast as the machine allows.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentProcessingIntegrationTest extends PostgresIntegrationTest {

    private static final int THREADS = 8;

    @Autowired
    private TrackingEventProcessor processor;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private TrackingEventRepository trackingEventRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    private UUID shipmentId;

    @BeforeEach
    void registerShipment() {
        notificationRepository.deleteAll();
        trackingEventRepository.deleteAll();
        shipmentRepository.deleteAll();
        shipmentId = shipmentRepository.saveAndFlush(Shipment.register(
                        UUID.randomUUID(), "retailer-1", "cust-1", "SP-CONCURRENT-1",
                        CarrierCode.SWIFTPOST, LocalDate.parse("2026-08-12"), CarrierEvents.T0))
                .getShipmentId();
    }

    @Test
    @DisplayName("eight distinct events for one shipment all land, and the highest sequence wins")
    void concurrentDistinctEventsAllSucceed() throws Exception {
        List<CarrierTrackingEventMessage> events = new ArrayList<>();
        for (int i = 1; i <= THREADS; i++) {
            events.add(CarrierEvents.swiftPost(shipmentId, "SP_TRANSIT", i).build());
        }

        List<TrackingEventProcessingResult> results = runConcurrently(
                events.stream().map(event -> (Callable<TrackingEventProcessingResult>)
                        () -> processor.process(event)).toList());

        // No lost updates and no lost events: every one is in history exactly once.
        assertThat(results).hasSize(THREADS);
        assertThat(trackingEventRepository.count()).isEqualTo(THREADS);

        Shipment shipment = shipmentRepository.findById(shipmentId).orElseThrow();
        // Whatever order the threads happened to win in, the highest sequence is the one that
        // sticks — that is the ordering rule holding under concurrency.
        assertThat(shipment.getLastSequenceNumber()).isEqualTo((long) THREADS);
        assertThat(shipment.getCurrentStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);

        // Every applied event bumped the version exactly once; no update was silently overwritten.
        long applied = trackingEventRepository.findAll().stream()
                .filter(e -> e.getProcessingStatus() == EventProcessingStatus.APPLIED)
                .count();
        assertThat(shipment.getVersion()).isEqualTo(applied);
    }

    @Test
    @DisplayName("the same event processed by eight threads stores one row and one notification")
    void concurrentDuplicatesCollapseToOne() throws Exception {
        var message = CarrierEvents.swiftPost(shipmentId, "SP_OFD", 5).build();

        List<TrackingEventProcessingResult> results = runConcurrently(
                java.util.Collections.nCopies(THREADS,
                        (Callable<TrackingEventProcessingResult>) () -> processor.process(message)));

        // This is the race the unique constraint exists for: several threads pass the pre-check,
        // one insert wins, and the losers are turned into no-ops rather than failures.
        assertThat(results).hasSize(THREADS);
        assertThat(results).filteredOn(r -> !r.duplicate()).hasSize(1);
        assertThat(results).filteredOn(TrackingEventProcessingResult::duplicate)
                .hasSize(THREADS - 1);

        assertThat(trackingEventRepository.count()).isEqualTo(1);
        assertThat(notificationRepository.count()).isEqualTo(1);
        assertThat(shipmentRepository.findById(shipmentId).orElseThrow().getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("retries stay bounded: no thread needs more attempts than the configured limit")
    void retriesAreBounded() throws Exception {
        List<CarrierTrackingEventMessage> events = new ArrayList<>();
        for (int i = 1; i <= THREADS; i++) {
            events.add(CarrierEvents.swiftPost(shipmentId, "SP_TRANSIT", i).build());
        }

        List<TrackingEventProcessingResult> results = runConcurrently(
                events.stream().map(event -> (Callable<TrackingEventProcessingResult>)
                        () -> processor.process(event)).toList());

        // max-optimistic-lock-retries is 3 in the test profile, so 4 attempts is the ceiling.
        // An unbounded loop would still pass the assertions above; this is what catches it.
        assertThat(results).allSatisfy(result ->
                assertThat(result.attempts()).isBetween(1, 4));
    }

    /**
     * Releases every task at the same instant and collects the results.
     *
     * <p>Any exception from a worker surfaces here through {@code Future.get}, so a thread that
     * exhausted its retries fails the test loudly rather than being silently absent from the
     * results.
     */
    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(tasks.size());

        try (ExecutorService executor = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = tasks.stream()
                    .map(task -> executor.submit(() -> {
                        barrier.await(30, TimeUnit.SECONDS);
                        return task.call();
                    }))
                    .toList();

            List<T> results = new ArrayList<>(futures.size());
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        }
    }
}
