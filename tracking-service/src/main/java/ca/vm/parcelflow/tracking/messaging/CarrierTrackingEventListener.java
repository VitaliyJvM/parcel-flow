package ca.vm.parcelflow.tracking.messaging;

import ca.vm.parcelflow.tracking.TrackingEventProcessingResult;
import ca.vm.parcelflow.tracking.TrackingEventProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Kafka entry point for carrier tracking events.
 *
 * <p>Transport only. It establishes the logging context, delegates to
 * {@link TrackingEventProcessor}, and reports the outcome. There is no branching on carrier or
 * event type here — that lives in the normalizer registry — and no database access, so the
 * processing pipeline can be exercised without a broker.
 *
 * <p><strong>Acknowledgement.</strong> The container uses {@code ack-mode: record}: the offset is
 * committed after this method returns normally, one record at a time. Manual acknowledgement was
 * considered and rejected — it would add a way to silently lose an offset commit (forget to call
 * {@code ack()}) while buying nothing, because the unit of work here is exactly one record and the
 * processor's transaction has already committed by the time this returns.
 *
 * <p>Offsets commit <em>after</em> the database transaction, which makes redelivery possible if the
 * process dies in the gap. That is the correct trade: at-least-once with a duplicate is
 * recoverable, at-most-once with a lost parcel scan is not. Stage 3 closes the loop by making
 * reprocessing idempotent.
 */
@Component
public class CarrierTrackingEventListener {

    private static final Logger log = LoggerFactory.getLogger(CarrierTrackingEventListener.class);

    private final TrackingEventProcessor processor;

    public CarrierTrackingEventListener(TrackingEventProcessor processor) {
        this.processor = processor;
    }

    @KafkaListener(
            topics = "${parcelflow.kafka.carrier-tracking-events-topic}",
            groupId = "${parcelflow.kafka.consumer-group}")
    public void onCarrierTrackingEvent(
            @Payload CarrierTrackingEventMessage message,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String messageKey,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        // MDC is populated before anything can fail so that exceptions logged elsewhere — by the
        // container's error handler, or by any code this calls into — still carry the identifiers
        // needed to find the event. Stage 4's JSON encoder renders these as first-class fields.
        try (var ignored = MDC.putCloseable("eventId", String.valueOf(message.eventId()));
                var ignored2 = MDC.putCloseable("shipmentId", String.valueOf(message.shipmentId()));
                var ignored3 =
                        MDC.putCloseable("carrierCode", String.valueOf(message.carrierCode()));
                var ignored4 =
                        MDC.putCloseable("correlationId", String.valueOf(message.correlationId()))) {

            // The identifiers are repeated in the message rather than left only in MDC: until
            // Stage 4 swaps in a structured encoder, the plain-text pattern does not render MDC,
            // and an ingest audit line that omits the event id is not much of an audit line.
            log.info("Received carrier event eventId={} shipmentId={} carrierCode={} "
                            + "correlationId={} type={} sequence={} partition={} offset={}",
                    message.eventId(), message.shipmentId(), message.carrierCode(),
                    message.correlationId(), message.eventType(), message.sequenceNumber(),
                    partition, offset);

            TrackingEventProcessingResult result = processor.process(message);

            log.info("Processed carrier event outcome={} normalized={} shipmentStatus={}",
                    result.processingStatus(),
                    result.normalizedEventType(),
                    result.shipmentStatusAfterProcessing());
        }
    }
}
