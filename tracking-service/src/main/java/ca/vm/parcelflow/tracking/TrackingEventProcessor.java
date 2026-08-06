package ca.vm.parcelflow.tracking;

import ca.vm.parcelflow.infrastructure.config.EventProcessingProperties;
import ca.vm.parcelflow.tracking.messaging.CarrierTrackingEventMessage;
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
 */
@Service
public class TrackingEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(TrackingEventProcessor.class);

    private final TrackingEventRecorder recorder;
    private final TrackingEventMetrics metrics;
    private final EventProcessingProperties properties;

    public TrackingEventProcessor(
            TrackingEventRecorder recorder,
            TrackingEventMetrics metrics,
            EventProcessingProperties properties) {
        this.recorder = recorder;
        this.metrics = metrics;
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
        metrics.eventReceived();

        int maxAttempts = properties.maxOptimisticLockRetries() + 1;

        for (int attempt = 1; ; attempt++) {
            try {
                TrackingEventProcessingResult result = recorder.record(message, attempt);
                recordOutcome(result);
                return result;

            } catch (DataIntegrityViolationException e) {
                // Lost the insert race for this event id. The winner has already stored it, so
                // there is nothing left to do. Logged without the stack trace: an expected
                // condition that prints a trace teaches operators to ignore traces.
                metrics.duplicateEvent();
                log.info("Event {} was already stored by a concurrent consumer; treating the "
                        + "redelivery as a no-op ({})", message.eventId(), e.getClass().getSimpleName());
                return duplicateAfterRace(message, attempt);

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

    /**
     * Re-reads the stored event after losing an insert race.
     *
     * <p>A second call into the recorder, which now takes its duplicate fast path. This is safe
     * from unbounded recursion because the winning transaction has committed by the time the
     * constraint fired, so the pre-check cannot miss.
     */
    private TrackingEventProcessingResult duplicateAfterRace(
            CarrierTrackingEventMessage message, int attempt) {
        TrackingEventProcessingResult result = recorder.record(message, attempt);
        recordOutcome(result);
        return result;
    }

    private void recordOutcome(TrackingEventProcessingResult result) {
        if (result.duplicate()) {
            metrics.duplicateEvent();
            log.info("Event {} already processed; no shipment or notification change", result.eventId());
        } else if (result.advancedShipment()) {
            metrics.eventApplied();
        } else {
            metrics.eventSuperseded();
            log.info("Event {} arrived out of order or after a terminal status; stored in history "
                    + "but shipment remains {}", result.eventId(), result.shipmentStatusAfterProcessing());
        }
    }
}
