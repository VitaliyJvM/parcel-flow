package ca.vm.parcelflow.simulator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Builds a carrier's event sequence and publishes it to Kafka. */
@Component
public class TrackingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TrackingEventPublisher.class);

    private final KafkaTemplate<String, CarrierTrackingEventMessage> kafkaTemplate;
    private final Clock clock;
    private final String topic;

    public TrackingEventPublisher(
            KafkaTemplate<String, CarrierTrackingEventMessage> kafkaTemplate,
            Clock clock,
            @Value("${parcelflow.kafka.carrier-tracking-events-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        this.topic = topic;
    }

    /**
     * Publishes the carrier's full normal sequence, waiting the configured delay between events.
     *
     * @return the events published, in order
     */
    public List<CarrierTrackingEventMessage> publish(SimulationRequest request)
            throws InterruptedException {

        List<CarrierTrackingEventMessage> events = buildEvents(request);

        log.info("Publishing {} {} events for shipment {} to topic '{}'",
                events.size(), request.carrierCode(), request.shipmentId(), topic);

        for (int i = 0; i < events.size(); i++) {
            CarrierTrackingEventMessage event = events.get(i);

            // Keyed by shipment id so every event for one parcel lands on one partition and is
            // therefore consumed in production order. Without this key the broker would round-robin
            // the journey across partitions and out-of-order arrival would become the norm rather
            // than the exception.
            kafkaTemplate.send(topic, event.shipmentId().toString(), event).join();

            log.info("Published eventId={} sequence={} type={} eventTime={} correlationId={}",
                    event.eventId(), event.sequenceNumber(), event.eventType(),
                    event.eventTime(), event.correlationId());

            boolean lastEvent = i == events.size() - 1;
            if (!lastEvent && !request.delayBetweenEvents().isZero()) {
                Thread.sleep(request.delayBetweenEvents());
            }
        }

        // Nothing is durable until the producer's buffer is flushed; without this a short-lived
        // CLI can exit with events still in memory.
        kafkaTemplate.flush();

        log.info("Published {} events for shipment {}", events.size(), request.shipmentId());
        return events;
    }

    /**
     * Event times are back-dated so the sequence reads as a real multi-day journey that has just
     * finished, rather than a burst of identical timestamps. The final event lands at "now" and
     * earlier ones are offset backwards by the script's plausible gaps.
     */
    List<CarrierTrackingEventMessage> buildEvents(SimulationRequest request) {
        List<CarrierEventScript.Step> steps =
                CarrierEventScript.forCarrier(request.carrierCode()).steps();

        Duration totalJourney = steps.stream()
                .map(CarrierEventScript.Step::elapsedSincePrevious)
                .reduce(Duration.ZERO, Duration::plus);

        Instant cursor = clock.instant().minus(totalJourney);

        List<CarrierTrackingEventMessage> events = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            CarrierEventScript.Step step = steps.get(i);
            cursor = cursor.plus(step.elapsedSincePrevious());

            events.add(new CarrierTrackingEventMessage(
                    UUID.randomUUID(),
                    CarrierTrackingEventMessage.SCHEMA_VERSION,
                    request.shipmentId(),
                    request.trackingNumber(),
                    request.carrierCode(),
                    step.carrierEventType(),
                    cursor,
                    // Sequence numbers start at 1: the contract requires a positive value, and
                    // carriers count scans from one.
                    i + 1L,
                    step.location(),
                    step.description(),
                    request.correlationId()));
        }
        return events;
    }
}
