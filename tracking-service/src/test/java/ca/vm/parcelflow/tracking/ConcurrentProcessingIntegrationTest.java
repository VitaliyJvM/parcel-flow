package ca.vm.parcelflow.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.carrier.normalization.CarrierEventNormalizers;
import ca.vm.parcelflow.infrastructure.config.EventProcessingProperties;
import ca.vm.parcelflow.notification.NotificationRepository;
import ca.vm.parcelflow.notification.domain.NotificationType;
import ca.vm.parcelflow.shipment.ShipmentRepository;
import ca.vm.parcelflow.shipment.domain.Shipment;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import ca.vm.parcelflow.support.CarrierEvents;
import ca.vm.parcelflow.support.PostgresIntegrationTest;
import ca.vm.parcelflow.tracking.domain.EventProcessingStatus;
import ca.vm.parcelflow.tracking.error.ErrorCategory;
import ca.vm.parcelflow.tracking.error.ProcessingErrorClassifier;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Optimistic locking under contention, in two halves.
 *
 * <p><b>Real concurrency.</b> Several threads drive {@link TrackingEventProcessor} at once. This
 * has to bypass Kafka, because Kafka cannot produce this situation for a single parcel: every event
 * for one shipment carries the same partition key, so the broker delivers them to one consumer
 * thread in order. The real situation this models is two <em>instances</em> of the service after a
 * rebalance, where one still holds a partition the other has been assigned. Synchronisation is a
 * {@link CyclicBarrier}, not a sleep, so the overlap is real rather than hoped for.
 *
 * <p>What real concurrency cannot do is promise how many times any one thread loses. With
 * {@value #THREADS} writers on one row, a thread can lose up to {@value #THREADS}{@code - 1} times
 * in a row, which is more than the in-process retry budget. Running out of that budget is
 * <em>intended</em>: the processor rethrows, and the Kafka error handler redelivers the record with
 * backoff. So the concurrent test models that redelivery explicitly and asserts its bound, rather
 * than assuming every thread wins within one call — the assumption that made the previous version
 * of this class fail intermittently in CI with {@code ObjectOptimisticLockingFailureException}.
 *
 * <p><b>Scripted conflicts.</b> The retry policy itself — one conflict is retried and succeeds, and
 * a conflict on every attempt exhausts the budget — is asserted with conflicts that are made to
 * happen, not hoped for. A spy on {@link CarrierEventNormalizers} commits a competing event from
 * inside the window between the recorder reading the shipment and flushing its update. The
 * competitor goes through the real processor in its own {@code REQUIRES_NEW} transaction, so the
 * conflict the recorder then hits is the genuine database version check, not a simulated
 * exception.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentProcessingIntegrationTest extends PostgresIntegrationTest {

    private static final int THREADS = 8;

    /** The event whose transaction gets raced. Its competitors are always {@code SP_TRANSIT}. */
    private static final String CONTESTED_EVENT_TYPE = "SP_OFD";
    private static final String COMPETITOR_EVENT_TYPE = "SP_TRANSIT";

    @Autowired
    private TrackingEventProcessor processor;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private TrackingEventRepository trackingEventRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private EventProcessingProperties properties;

    @Autowired
    private ProcessingErrorClassifier classifier;

    /** Calls the real normalizers unless a test scripts a conflict; reset after every test. */
    @MockitoSpyBean
    private CarrierEventNormalizers normalizers;

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

    // --- real concurrency -------------------------------------------------------------------

    @Test
    @DisplayName("eight distinct events for one shipment all land, and the highest sequence wins")
    void concurrentDistinctEventsAllSucceed() throws Exception {
        List<CarrierTrackingEventMessage> events = new ArrayList<>();
        for (int i = 1; i <= THREADS; i++) {
            events.add(CarrierEvents.swiftPost(shipmentId, COMPETITOR_EVENT_TYPE, i).build());
        }

        List<Delivered> delivered = runConcurrently(
                events.stream().map(event -> (Callable<Delivered>)
                        () -> deliverUntilProcessed(event)).toList());

        // No lost updates and no lost events: every one is in history exactly once.
        assertThat(delivered).hasSize(THREADS);
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

        // Retries stayed bounded at both levels. No single processor call ran more attempts than
        // its budget, and no event needed more attempts in total than there were other events to
        // lose to (see deliverUntilProcessed for why that is a hard bound, not a likely one).
        assertThat(delivered).allSatisfy(d -> {
            assertThat(d.result().attempts()).isBetween(1, maxAttempts());
            assertThat(d.totalAttempts()).isBetween(1, THREADS);
        });
    }

    @Test
    @DisplayName("the same event processed by eight threads stores one row and one notification")
    void concurrentDuplicatesCollapseToOne() throws Exception {
        var message = CarrierEvents.swiftPost(shipmentId, CONTESTED_EVENT_TYPE, 5).build();

        // Not exposed to retry exhaustion, so no redelivery is modelled: the threads race on the
        // event-id unique constraint, whose INSERT Hibernate flushes before the shipment UPDATE. A
        // loser therefore fails on the insert — handled as a duplicate — before it can reach the
        // version check, and only the single winner ever updates the shipment row.
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

    // --- scripted conflicts -----------------------------------------------------------------

    @Test
    @DisplayName("one conflict is retried in a fresh transaction that re-decides against the winner")
    void conflictIsRetriedAgainstTheWinningState() {
        var contested = CarrierEvents.swiftPost(shipmentId, CONTESTED_EVENT_TYPE, 2).build();
        AtomicInteger conflicts = commitCompetingEventDuringEachAttempt(1);

        TrackingEventProcessingResult result = processor.process(contested);

        // Attempt 1 lost to the competitor (sequence 1); attempt 2 re-read the shipment, found
        // sequence 2 still newer, and applied it.
        assertThat(conflicts).hasValue(1);
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.duplicate()).isFalse();
        assertThat(result.processingStatus()).isEqualTo(EventProcessingStatus.APPLIED);

        Shipment shipment = shipmentRepository.findById(shipmentId).orElseThrow();
        assertThat(shipment.getCurrentStatus()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
        assertThat(shipment.getLastSequenceNumber()).isEqualTo(2L);
        // One bump for the competitor, one for the retry. The failed attempt wrote nothing.
        assertThat(shipment.getVersion()).isEqualTo(2L);

        // The failed attempt's history row was rolled back with it: competitor + contested, once
        // each. Only OUT_FOR_DELIVERY notifies, and it did so exactly once.
        assertThat(trackingEventRepository.count()).isEqualTo(2);
        assertThat(notificationRepository.findAll())
                .singleElement()
                .satisfies(n -> assertThat(n.getNotificationType())
                        .isEqualTo(NotificationType.OUT_FOR_DELIVERY));
    }

    @Test
    @DisplayName("a conflict on every attempt exhausts the budget and hands the event back to Kafka")
    void retriesAreBoundedAndExhaustionIsHandedBack() {
        int maxAttempts = maxAttempts();
        var contested = CarrierEvents.swiftPost(shipmentId, CONTESTED_EVENT_TYPE, 100).build();
        AtomicInteger conflicts = commitCompetingEventDuringEachAttempt(maxAttempts);

        ObjectOptimisticLockingFailureException exhausted = catchThrowableOfType(
                ObjectOptimisticLockingFailureException.class, () -> processor.process(contested));

        // Exactly the configured number of attempts: one conflict each, and no further attempt.
        assertThat(exhausted).isNotNull();
        assertThat(conflicts).hasValue(maxAttempts);

        // Every attempt rolled back as a unit: no history row, no notification for the contested
        // event. The shipment holds only what the competitors (sequences 1..max) committed.
        assertThat(trackingEventRepository.findByEventId(contested.eventId())).isEmpty();
        assertThat(trackingEventRepository.count()).isEqualTo(maxAttempts);
        assertThat(notificationRepository.count()).isZero();
        Shipment afterExhaustion = shipmentRepository.findById(shipmentId).orElseThrow();
        assertThat(afterExhaustion.getCurrentStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(afterExhaustion.getLastSequenceNumber()).isEqualTo((long) maxAttempts);
        assertThat(afterExhaustion.getVersion()).isEqualTo(maxAttempts);

        // Handed upstream as retryable: the classifier the Kafka error handler and the failed-event
        // store share calls it a concurrency conflict worth redelivering, and it is not on the
        // handler's skip-the-retries list.
        assertThat(classifier.classify(exhausted)).isEqualTo(ErrorCategory.CONCURRENCY_CONFLICT);
        assertThat(ErrorCategory.CONCURRENCY_CONFLICT.isRetryableAutomatically()).isTrue();
        assertThat(ProcessingErrorClassifier.NON_RETRYABLE_EXCEPTIONS)
                .noneMatch(type -> type.isInstance(exhausted));

        // The redelivery, with the contention gone (the script's budget is spent), lands on the
        // first attempt and the event is applied exactly once.
        TrackingEventProcessingResult redelivered = processor.process(contested);
        assertThat(conflicts).hasValue(maxAttempts);
        assertThat(redelivered.attempts()).isEqualTo(1);
        assertThat(redelivered.processingStatus()).isEqualTo(EventProcessingStatus.APPLIED);
        Shipment afterRedelivery = shipmentRepository.findById(shipmentId).orElseThrow();
        assertThat(afterRedelivery.getCurrentStatus()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
        assertThat(afterRedelivery.getVersion()).isEqualTo(maxAttempts + 1L);
        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    // --- helpers ----------------------------------------------------------------------------

    private int maxAttempts() {
        return properties.maxOptimisticLockRetries() + 1;
    }

    /**
     * Scripts {@code conflicts} optimistic-lock conflicts for the contested event, one per attempt.
     *
     * <p>The recorder reads the shipment, then normalizes the event code, then applies the event
     * and flushes. The spy runs inside that window: before handing back the real normalization, it
     * processes a competing {@code SP_TRANSIT} event with the next sequence number through the real
     * processor. That call opens its own {@code REQUIRES_NEW} transaction — suspending, not
     * joining, the contested one — and commits a version bump. The contested attempt's flush then
     * fails the {@code @Version} check exactly as it would against another instance.
     *
     * <p>The competitor is applied (each has a higher sequence than the last) and never conflicts
     * itself: nothing else writes while it runs, and the contested transaction has only read the
     * row. Competitor normalizations pass straight through, so the script cannot recurse.
     *
     * @return the number of conflicts injected so far
     */
    private AtomicInteger commitCompetingEventDuringEachAttempt(int conflicts) {
        AtomicInteger injected = new AtomicInteger();
        AtomicLong nextCompetitorSequence = new AtomicLong(1);
        doAnswer(invocation -> {
            if (CONTESTED_EVENT_TYPE.equals(invocation.getArgument(1))
                    && injected.get() < conflicts) {
                injected.incrementAndGet();
                TrackingEventProcessingResult competitor = processor.process(CarrierEvents
                        .swiftPost(shipmentId, COMPETITOR_EVENT_TYPE,
                                nextCompetitorSequence.getAndIncrement())
                        .build());
                // Guards the premise: a competitor that did not commit a change would not cause
                // the conflict the test goes on to assert.
                assertThat(competitor.advancedShipment()).isTrue();
                assertThat(competitor.attempts()).isEqualTo(1);
            }
            return invocation.callRealMethod();
        }).when(normalizers).normalize(any(), any());
        return injected;
    }

    /** One event's journey through {@link #deliverUntilProcessed}. */
    private record Delivered(TrackingEventProcessingResult result, int deliveries, int totalAttempts) {
    }

    /**
     * Processes an event the way the listener container does: if the processor gives up on an
     * optimistic-lock conflict, the record is delivered again.
     *
     * <p>Only {@link OptimisticLockingFailureException} is redelivered — any other exception fails
     * the test — and the number of deliveries is bounded by an argument, not a guess:
     *
     * <ul>
     *   <li>An attempt fails the version check only if another transaction committed a change to
     *       the shipment after that attempt read it.
     *   <li>Successive attempts of one event read strictly after the previous one failed, so each
     *       failure is caused by a <em>different</em> competing commit.
     *   <li>Each of the other {@code THREADS - 1} events changes the shipment at most once (when it
     *       is applied; a superseded event writes no shipment update and cannot cause a conflict).
     * </ul>
     *
     * <p>So one event fails at most {@code THREADS - 1} attempts and needs at most {@code THREADS}
     * attempts in total, i.e. at most {@code ceil(THREADS / maxAttempts)} deliveries. Exceeding that
     * would mean conflicts with no competing commit to explain them — a real defect, reported as a
     * failure. The bound is also checked against the Kafka retry budget of the test profile, so the
     * modelled redelivery is one the real error handler would actually perform.
     */
    private Delivered deliverUntilProcessed(CarrierTrackingEventMessage event) {
        int maxAttempts = maxAttempts();
        int maxDeliveries = Math.ceilDiv(THREADS, maxAttempts);
        assertThat(maxDeliveries)
                .as("deliveries this contention can need vs. 1 + max-kafka-retries")
                .isLessThanOrEqualTo(1 + properties.maxKafkaRetries());

        int failedAttempts = 0;
        for (int delivery = 1; ; delivery++) {
            try {
                TrackingEventProcessingResult result = processor.process(event);
                return new Delivered(result, delivery, failedAttempts + result.attempts());
            } catch (OptimisticLockingFailureException e) {
                failedAttempts += maxAttempts;
                if (delivery >= maxDeliveries) {
                    throw new AssertionError("Event %s lost %d attempts over %d deliveries; at most %d "
                            .formatted(event.eventId(), failedAttempts, delivery, THREADS - 1)
                            + "competing commits exist to cause that", e);
                }
            }
        }
    }

    /**
     * Releases every task at the same instant and collects the results.
     *
     * <p>Any exception from a worker surfaces here through {@code Future.get}, so a failure fails
     * the test loudly rather than being silently absent from the results.
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
