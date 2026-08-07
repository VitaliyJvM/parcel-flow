package ca.vm.parcelflow.tracking.failure;

import ca.vm.parcelflow.infrastructure.observability.LogContext;
import ca.vm.parcelflow.tracking.TrackingEventMetrics;
import ca.vm.parcelflow.tracking.error.ProcessingErrorClassifier;
import ca.vm.parcelflow.tracking.messaging.CarrierTrackingEventMessage;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * What happens to a record once retries are exhausted or the failure is known to be permanent.
 *
 * <p>Two things, in this order:
 *
 * <ol>
 *   <li>Persist a {@link FailedEvent} row, so the failure is queryable and has an operator workflow
 *       attached to it.
 *   <li>Publish the record to the dead letter topic, so the message itself survives and can be
 *       replayed in bulk.
 * </ol>
 *
 * <p>The database write comes first deliberately. If the broker is the thing that is broken, the
 * DLT publish is exactly what will fail — and losing the explanation as well as the message is
 * strictly worse than losing only the message. The row is the durable record; the topic is the
 * convenience.
 *
 * <p>A failure to record must never propagate: throwing here would make the container retry the
 * whole batch, and a poison record would stop the consumer for good. Everything is caught and
 * logged.
 *
 * <p>This runs on the consumer thread <em>after</em> the listener's logging context has been closed,
 * so it establishes its own. Without that, the one log line an operator most needs to correlate —
 * "this event was dead-lettered" — would be the only line in the flow with no correlation id on it.
 */
@Component
public class FailedEventRecoverer implements ConsumerRecordRecoverer {

    private static final Logger log = LoggerFactory.getLogger(FailedEventRecoverer.class);

    private final FailedEventStore store;
    private final TrackingEventMetrics metrics;
    private final ProcessingErrorClassifier classifier;
    private final JsonMapper jsonMapper;

    /**
     * The dead letter publish, injected as a function rather than the concrete
     * {@code DeadLetterPublishingRecoverer}. That keeps this class testable without a broker: a
     * test can assert the failed-event row is written and the publish was attempted, without
     * standing up a producer.
     */
    private final BiConsumer<ConsumerRecord<?, ?>, Exception> deadLetterPublisher;

    public FailedEventRecoverer(
            FailedEventStore store,
            TrackingEventMetrics metrics,
            ProcessingErrorClassifier classifier,
            JsonMapper jsonMapper,
            BiConsumer<ConsumerRecord<?, ?>, Exception> deadLetterPublisher) {
        this.store = store;
        this.metrics = metrics;
        this.classifier = classifier;
        this.jsonMapper = jsonMapper;
        this.deadLetterPublisher = deadLetterPublisher;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        UUID eventId = null;
        UUID shipmentId = null;
        String carrierCode = null;
        String correlationId = null;
        boolean deserialized = record.value() instanceof CarrierTrackingEventMessage;

        if (record.value() instanceof CarrierTrackingEventMessage message) {
            eventId = message.eventId();
            shipmentId = message.shipmentId();
            carrierCode = String.valueOf(message.carrierCode());
            correlationId = message.correlationId();
        }

        try (var ignored = LogContext.forCarrierEvent(
                correlationId, eventId, shipmentId, carrierCode,
                record.topic(), record.partition(), record.offset())) {

            // A record that never deserialized never reached the processor, so nothing has counted
            // it as a failure yet. One increment here closes that gap without double-counting the
            // failures the processor already saw.
            if (!deserialized) {
                metrics.eventFailed(classifier.classify(exception));
            }

            recordFailure(record, exception, eventId, shipmentId);
            publishDeadLetter(record, exception, eventId);
        }
    }

    private void recordFailure(
            ConsumerRecord<?, ?> record, Exception exception, UUID eventId, UUID shipmentId) {
        try {
            store.recordFailure(
                    eventId,
                    shipmentId,
                    payloadOf(record),
                    exception,
                    record.topic(),
                    record.partition(),
                    record.offset());
        } catch (RuntimeException e) {
            log.error("Could not persist the failed-event record for {}-{}@{}; the message will "
                            + "still be dead-lettered",
                    record.topic(), record.partition(), record.offset(), e);
        }
    }

    private void publishDeadLetter(ConsumerRecord<?, ?> record, Exception exception, UUID eventId) {
        try {
            deadLetterPublisher.accept(record, exception);
            metrics.eventDeadLettered();
            log.warn("Dead-lettered {}-{}@{} (eventId={}) after {}",
                    record.topic(), record.partition(), record.offset(), eventId,
                    exception.getClass().getSimpleName());
        } catch (RuntimeException e) {
            log.error("Could not publish {}-{}@{} to the dead letter topic; the failed-event row "
                            + "in PostgreSQL remains the record of this failure",
                    record.topic(), record.partition(), record.offset(), e);
        }
    }

    /**
     * Renders the record value as text for storage.
     *
     * <p>A record that failed deserialization still has its raw bytes, and those are the most
     * valuable thing to keep — they are what the operator needs to see to work out what the
     * producer sent. A record that deserialized is re-serialized back to JSON so the stored payload
     * is something the manual retry can read back.
     *
     * <p>The payload is <em>stored</em>, never logged. It is the one field in the pipeline that can
     * contain arbitrary carrier data, so it stays in a table an operator has to ask for rather than
     * in a log stream that gets shipped somewhere and indexed.
     */
    private String payloadOf(ConsumerRecord<?, ?> record) {
        Object value = record.value();
        if (value == null) {
            return "";
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (RuntimeException e) {
            log.warn("Could not serialize the failed payload for storage; falling back to toString: {}",
                    e.getMessage());
            return String.valueOf(value);
        }
    }
}
