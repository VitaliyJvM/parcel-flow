package ca.vm.parcelflow.tracking;

import ca.vm.parcelflow.infrastructure.config.EventProcessingProperties;
import ca.vm.parcelflow.tracking.error.ProcessingErrorClassifier;
import ca.vm.parcelflow.tracking.messaging.CarrierTrackingEventMessage;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Entry point for processing a carrier event.
 *
 * <p>Holds no transaction of its own. Everything it does — retrying, and turning a constraint
 * violation into a duplicate — has to happen <em>outside</em> the transaction that failed, because
 * both conditions arrive only after that transaction has been marked rollback-only. Doing this work
 * inside {@link TrackingEventRecorder} would produce an {@code UnexpectedRollbackException} at
 * commit instead of the intended behaviour.
 *
 * <p>Two conditions are handled here:
 *
 * <ul>
 *   <li><b>Duplicate delivery.</b> The recorder pre-checks for an existing event id, but two
 *       threads can both pass that check. The loser's insert violates the unique constraint, and
 *       this class turns that into a successful no-op. A duplicate is not an error: the work was
 *       already done.
 *   <li><b>Optimistic-lock conflict.</b> Another thread updated the shipment first. Retried a
 *       bounded number of times with a fresh transaction each attempt, which re-reads the shipment
 *       and re-runs the ordering decision against the state that actually won.
 * </ul>
 *
 * <p>This is also where the pipeline is measured. The timer spans the whole call including retries,
 * because the question a latency panel answers is "how long did this event take to land", not "how
 * long did the attempt that happened to win take". Failures are counted here, once per attempt at
 * this method, using the same {@link ProcessingErrorClassifier} that decides the retry policy — so
 * the {@code category} on a failure metric always agrees with the category on the stored
 * failed-event row.
 */
@Service
public class TrackingEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(TrackingEventProcessor.class);

    private final TrackingEventRecorder recorder;
    private final TrackingEventMetrics metrics;
    private final ProcessingErrorClassifier classifier;
    private final EventProcessingProperties properties;

    public TrackingEventProcessor(
            TrackingEventRecorder recorder,
            TrackingEventMetrics metrics,
            ProcessingErrorClassifier classifier,
            EventProcessingProperties properties) {
        this.recorder = recorder;
        this.metrics = metrics;
        this.classifier = classifier;
        this.properties = properties;
    }

    /**
     * @throws InvalidCarrierEventException if the message is structurally unusable
     * @throws ca.vm.parcelflow.shipment.ShipmentNotFoundException if the shipment is unknown
     * @throws CarrierMismatchException if the carrier disagrees with the shipment's
     * @throws OptimisticLockingFailureException if the bounded retries are exhausted, so the Kafka
     *     error handler can apply its own backoff rather than this loop spinning
     */
    public TrackingEventProcessingResult process(CarrierTrackingEventMessage message) {
        Timer.Sample sample = metrics.startProcessing();
        try {
            TrackingEventProcessingResult result = attemptWithRetries(message);
            recordOutcome(sample, result);
            return result;
        } catch (RuntimeException e) {
            metrics.recordFailure(sample, classifier.classify(e));
            throw e;
        }
    }

    private TrackingEventProcessingResult attemptWithRetries(CarrierTrackingEventMessage message) {
        int maxAttempts = properties.maxOptimisticLockRetries() + 1;

        for (int attempt = 1; ; attempt++) {
            try {
                return recorder.record(message, attempt);

            } catch (DataIntegrityViolationException e) {
                // Lost the insert race for this event id. The winner has already stored it, so
                // there is nothing left to do. Logged without the stack trace: an expected
                // condition that prints a trace teaches operators to ignore traces. Not counted
                // here — the re-read below returns a duplicate result, which recordOutcome counts.
                log.info("Event {} was already stored by a concurrent consumer; treating the "
                        + "redelivery as a no-op ({})", message.eventId(), e.getClass().getSimpleName());
                // A second call into the recorder, which now takes its duplicate fast path. Safe
                // from unbounded recursion because the winning transaction has committed by the
                // time the constraint fired, so the pre-check cannot miss.
                return recorder.record(message, attempt);

            } catch (OptimisticLockingFailureException e) {
                metrics.optimisticLockConflict();
                if (attempt >= maxAttempts) {
                    // Bounded, deliberately. An unbounded loop under sustained contention is a
                    // livelock that consumes a consumer thread forever. Giving up hands the record
                    // back to the Kafka error handler, whose backoff spaces out the next attempt
                    // instead of hammering the row.
                    log.warn("Giving up on event {} after {} optimistic-lock conflicts",
                            message.eventId(), attempt);
                    throw e;
                }
                log.debug("Optimistic-lock conflict on event {}, attempt {} of {}; retrying",
                        message.eventId(), attempt, maxAttempts);
            }
        }
    }

    private void recordOutcome(Timer.Sample sample, TrackingEventProcessingResult result) {
        if (result.duplicate()) {
            metrics.recordDuplicate(sample);
            log.info("Event {} already processed; no shipment or notification change", result.eventId());
        } else if (result.advancedShipment()) {
            metrics.recordApplied(sample);
        } else {
            metrics.recordOutOfOrder(sample);
            log.info("Event {} arrived out of order or after a terminal status; stored in history "
                    + "but shipment remains {}", result.eventId(), result.shipmentStatusAfterProcessing());
        }
    }
}
