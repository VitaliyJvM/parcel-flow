package ca.vm.parcelflow.tracking.failure;

import ca.vm.parcelflow.tracking.TrackingEventMetrics;
import ca.vm.parcelflow.tracking.error.ErrorCategory;
import ca.vm.parcelflow.tracking.error.ProcessingErrorClassifier;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every transactional operation on {@link FailedEvent}.
 *
 * <p>A separate bean from {@link FailedEventService} because the retry flow needs several small,
 * independently-committing state changes — claim, then reprocess, then resolve — and a
 * {@code @Transactional} method calling another on {@code this} would silently run without a proxy
 * and therefore without a transaction boundary at all. Crossing a bean boundary is what makes each
 * step its own transaction.
 *
 * <p>The steps must commit separately: if the claim, the reprocessing and the outcome shared one
 * transaction, a failed retry would roll back the retry-count increment that documents it.
 */
@Service
public class FailedEventStore {

    private static final Logger log = LoggerFactory.getLogger(FailedEventStore.class);

    private final FailedEventRepository failedEventRepository;
    private final ProcessingErrorClassifier classifier;
    private final TrackingEventMetrics metrics;
    private final Clock clock;

    public FailedEventStore(
            FailedEventRepository failedEventRepository,
            ProcessingErrorClassifier classifier,
            TrackingEventMetrics metrics,
            Clock clock) {
        this.failedEventRepository = failedEventRepository;
        this.classifier = classifier;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Stores or updates the record for a failed event.
     *
     * <p>{@code REQUIRES_NEW} because this is called from the Kafka error handler after the
     * processing transaction has already rolled back. Joining that transaction would mean the
     * record of the failure disappears along with the failure it documents.
     *
     * <p>Upserts on {@code eventId}: an event that fails, is retried by hand and fails again is one
     * row with a growing retry count and a widening first-to-last window, not a pile of
     * near-identical rows an operator has to correlate.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailedEvent recordFailure(
            UUID eventId,
            UUID shipmentId,
            String payload,
            Throwable failure,
            String topic,
            int partition,
            long offset) {

        ErrorCategory category = classifier.classify(failure);
        Throwable reported = unwrap(failure);
        metrics.processingFailed(category);

        Optional<FailedEvent> existing = eventId == null
                ? Optional.empty()
                : failedEventRepository.findByEventId(eventId);

        if (existing.isPresent()) {
            FailedEvent failedEvent = existing.get();
            failedEvent.recordRepeatFailure(
                    category, reported.getClass().getName(), reported.getMessage(), clock.instant());
            log.warn("Event {} failed again: category={} type={}",
                    eventId, category, reported.getClass().getSimpleName());
            return failedEventRepository.save(failedEvent);
        }

        FailedEvent failedEvent = failedEventRepository.save(FailedEvent.builder()
                .eventId(eventId)
                .shipmentId(shipmentId)
                .payload(payload)
                .errorCategory(category)
                .errorType(reported.getClass().getName())
                .errorMessage(reported.getMessage())
                .origin(topic, partition, offset)
                .failedAt(clock.instant())
                .build());

        log.warn("Recorded failed event {} (eventId={}, category={}, autoRetryable={}) from {}-{}@{}",
                failedEvent.getFailedEventId(), eventId, category,
                category.isRetryableAutomatically(), topic, partition, offset);
        return failedEvent;
    }

    @Transactional(readOnly = true)
    public Page<FailedEvent> findFailedEvents(FailedEventStatus status, int page, int size) {
        PageRequest pageRequest = PageRequest.of(
                page, size, Sort.by(Sort.Order.desc("lastFailedAt"), Sort.Order.asc("failedEventId")));
        return status == null
                ? failedEventRepository.findAll(pageRequest)
                : failedEventRepository.findByStatus(status, pageRequest);
    }

    @Transactional(readOnly = true)
    public FailedEvent getFailedEvent(UUID failedEventId) {
        return failedEventRepository
                .findById(failedEventId)
                .orElseThrow(() -> new FailedEventExceptions.NotFound(failedEventId));
    }

    /** @return true if this caller claimed the event, false if someone else already had it */
    @Transactional
    public boolean claimForRetry(UUID failedEventId) {
        return failedEventRepository.claimForRetry(failedEventId) == 1;
    }

    @Transactional
    public void markResolved(UUID failedEventId) {
        FailedEvent failedEvent = getFailedEvent(failedEventId);
        failedEvent.markResolved();
        failedEventRepository.save(failedEvent);
    }

    /** Returns the record to {@code FAILED} and increments its retry count. */
    @Transactional
    public FailedEvent markRetryFailed(UUID failedEventId, Throwable failure) {
        FailedEvent failedEvent = getFailedEvent(failedEventId);
        Throwable reported = unwrap(failure);
        ErrorCategory category = classifier.classify(failure);
        metrics.processingFailed(category);
        failedEvent.markRetryFailed(
                category, reported.getClass().getName(), reported.getMessage(), clock.instant());
        return failedEventRepository.save(failedEvent);
    }

    private static Throwable unwrap(Throwable failure) {
        return failure.getCause() != null ? failure.getCause() : failure;
    }
}
