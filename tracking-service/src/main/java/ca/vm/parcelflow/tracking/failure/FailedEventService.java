package ca.vm.parcelflow.tracking.failure;

import ca.vm.parcelflow.tracking.TrackingEventProcessingResult;
import ca.vm.parcelflow.tracking.TrackingEventProcessor;
import ca.vm.parcelflow.tracking.error.ErrorCategory;
import ca.vm.parcelflow.tracking.messaging.CarrierTrackingEventMessage;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads failed events and drives manual retries.
 *
 * <p>Deliberately not transactional. The retry is a sequence of independently-committing steps
 * around a call into the normal processing pipeline, and wrapping the whole thing in one
 * transaction would both hold a connection open across the reprocessing and roll back the retry
 * bookkeeping whenever the retry failed — losing the record of the attempt.
 */
@Service
public class FailedEventService {

    private static final Logger log = LoggerFactory.getLogger(FailedEventService.class);

    private final FailedEventStore store;
    private final TrackingEventProcessor processor;
    private final JsonMapper jsonMapper;

    public FailedEventService(
            FailedEventStore store, TrackingEventProcessor processor, JsonMapper jsonMapper) {
        this.store = store;
        this.processor = processor;
        this.jsonMapper = jsonMapper;
    }

    public Page<FailedEvent> findFailedEvents(FailedEventStatus status, int page, int size) {
        return store.findFailedEvents(status, page, size);
    }

    public FailedEvent getFailedEvent(UUID failedEventId) {
        return store.getFailedEvent(failedEventId);
    }

    /**
     * Reprocesses a failed event through the normal pipeline.
     *
     * <p><b>Approach: reprocess in-process rather than republish to Kafka.</b> Republishing would
     * reuse the consumer path, but the API could then only answer "accepted" and the operator would
     * have to go looking for the outcome. Calling the processor directly returns the real result —
     * applied, superseded or duplicate — in the HTTP response.
     *
     * <p>The trade-off is that a direct call bypasses partition ordering, so the event is no longer
     * serialized against other events for the same parcel. Acceptable on two grounds: the event
     * already lost its place in the ordering when it failed, and the domain's ordering rules still
     * refuse to let a stale event move the shipment backwards. Optimistic locking still protects
     * the row against a concurrent consumer.
     *
     * <p>Idempotency is unchanged. Reprocessing an event that did eventually get stored takes the
     * recorder's duplicate path and changes nothing.
     *
     * @throws FailedEventExceptions.NotFound if there is no such record
     * @throws FailedEventExceptions.NotRetryable if the category can never succeed on retry
     * @throws FailedEventExceptions.RetryNotAvailable if another retry already holds the record
     */
    public FailedEventRetryOutcome retry(UUID failedEventId) {
        FailedEvent failedEvent = store.getFailedEvent(failedEventId);

        if (!failedEvent.getErrorCategory().isRetryableManually()) {
            throw new FailedEventExceptions.NotRetryable(failedEventId, failedEvent.getErrorCategory());
        }
        if (failedEvent.getStatus() != FailedEventStatus.FAILED) {
            throw new FailedEventExceptions.RetryNotAvailable(failedEventId, failedEvent.getStatus());
        }

        // Atomic claim. Anything that slips in between the read above and this statement —
        // including a second operator's request — loses here rather than double-processing.
        if (!store.claimForRetry(failedEventId)) {
            throw new FailedEventExceptions.RetryNotAvailable(
                    failedEventId, store.getFailedEvent(failedEventId).getStatus());
        }

        CarrierTrackingEventMessage message;
        try {
            message = jsonMapper.readValue(failedEvent.getPayload(), CarrierTrackingEventMessage.class);
        } catch (RuntimeException e) {
            // The payload never parsed in the first place. Release the claim so the record does not
            // sit in RETRYING forever, then report it as what it is.
            store.markRetryFailed(failedEventId, e);
            throw new FailedEventExceptions.NotRetryable(failedEventId, ErrorCategory.MALFORMED_PAYLOAD);
        }

        try {
            TrackingEventProcessingResult result = processor.process(message);
            store.markResolved(failedEventId);
            log.info("Manual retry of failed event {} succeeded: {}",
                    failedEventId, result.processingStatus());
            return new FailedEventRetryOutcome(failedEventId, true, result, null);

        } catch (RuntimeException e) {
            FailedEvent updated = store.markRetryFailed(failedEventId, e);
            log.warn("Manual retry of failed event {} failed again: {}",
                    failedEventId, e.getClass().getSimpleName());
            return new FailedEventRetryOutcome(failedEventId, false, null, updated.getErrorCategory());
        }
    }

    /**
     * @param result the processing result when the retry succeeded, otherwise null
     * @param errorCategory the classification of the new failure when the retry failed
     */
    public record FailedEventRetryOutcome(
            UUID failedEventId,
            boolean succeeded,
            TrackingEventProcessingResult result,
            ErrorCategory errorCategory) {
    }
}
