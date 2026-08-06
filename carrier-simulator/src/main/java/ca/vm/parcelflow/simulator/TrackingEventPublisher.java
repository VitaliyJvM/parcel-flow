package ca.vm.parcelflow.simulator;

import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Publishes a scenario's events to Kafka. */
@Component
public class TrackingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TrackingEventPublisher.class);

    private final KafkaTemplate<String, CarrierTrackingEventMessage> kafkaTemplate;
    private final ScenarioEventFactory eventFactory;
    private final String topic;

    public TrackingEventPublisher(
            KafkaTemplate<String, CarrierTrackingEventMessage> kafkaTemplate,
            ScenarioEventFactory eventFactory,
            @Value("${parcelflow.kafka.carrier-tracking-events-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.eventFactory = eventFactory;
        this.topic = topic;
    }

    /**
     * @return the events published, in the order they were sent
     */
    public List<CarrierTrackingEventMessage> publish(SimulationRequest request)
            throws InterruptedException {

        List<CarrierTrackingEventMessage> events = eventFactory.eventsFor(request);

        // RAPID_CONCURRENT_EVENTS ignores the delay by definition — pacing it would defeat the
        // scenario.
        Duration delay = request.scenario() == Scenario.RAPID_CONCURRENT_EVENTS
                ? Duration.ZERO
                : request.delayBetweenEvents();

        log.info("Publishing {} {} events for shipment {} to topic '{}' (scenario={}, seed={})",
                events.size(), request.carrierCode(), request.shipmentId(), topic,
                request.scenario(), request.seed());

        for (int i = 0; i < events.size(); i++) {
            CarrierTrackingEventMessage event = events.get(i);

            // Keyed by shipment id so every event for one parcel lands on one partition and is
            // therefore consumed in production order. Without this key the broker would round-robin
            // the journey across partitions and out-of-order arrival would become the norm rather
            // than something the OUT_OF_ORDER scenario has to manufacture.
            kafkaTemplate.send(topic, event.shipmentId().toString(), event).join();

            log.info("Published eventId={} sequence={} type={} eventTime={} correlationId={}",
                    event.eventId(), event.sequenceNumber(), event.eventType(),
                    event.eventTime(), event.correlationId());

            boolean lastEvent = i == events.size() - 1;
            if (!lastEvent && !delay.isZero()) {
                Thread.sleep(delay);
            }
        }

        // Nothing is durable until the producer's buffer is flushed; without this a short-lived
        // CLI can exit with events still in memory.
        kafkaTemplate.flush();

        log.info("Published {} events for shipment {}", events.size(), request.shipmentId());
        return events;
    }
}
