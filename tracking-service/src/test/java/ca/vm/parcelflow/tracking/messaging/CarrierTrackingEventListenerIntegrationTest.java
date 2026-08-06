package ca.vm.parcelflow.tracking.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.shipment.ShipmentRepository;
import ca.vm.parcelflow.shipment.domain.Shipment;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import ca.vm.parcelflow.support.KafkaIntegrationTest;
import ca.vm.parcelflow.tracking.TrackingEventRepository;
import ca.vm.parcelflow.tracking.domain.EventProcessingStatus;
import ca.vm.parcelflow.tracking.domain.TrackingEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The real transport path: publish JSON to a real broker, and assert it lands in PostgreSQL.
 *
 * <p>The payload is a hand-written JSON string, not a serialized object. Serializing the consumer's
 * own record type would let producer and consumer agree with each other by construction and prove
 * nothing about the wire format; a literal document catches a renamed field, a changed date
 * encoding, or an enum spelled differently.
 *
 * <p>All waiting is {@link org.awaitility.Awaitility} polling with a bounded timeout. A
 * {@code Thread.sleep} would either be too short on a loaded machine or waste time on a fast one.
 */
@SpringBootTest
@ActiveProfiles("test")
class CarrierTrackingEventListenerIntegrationTest extends KafkaIntegrationTest {

    /** Generous: it covers consumer group formation and partition assignment on a cold broker. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final Instant T0 = Instant.parse("2026-08-01T10:00:00Z");

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private TrackingEventRepository trackingEventRepository;

    @Value("${parcelflow.kafka.carrier-tracking-events-topic}")
    private String topic;

    private UUID shipmentId;

    @BeforeEach
    void registerShipment() {
        trackingEventRepository.deleteAll();
        shipmentRepository.deleteAll();
        shipmentId = shipmentRepository.saveAndFlush(Shipment.register(
                        UUID.randomUUID(),
                        "retailer-1",
                        "cust-1",
                        "SP-KAFKA-1",
                        CarrierCode.SWIFTPOST,
                        LocalDate.parse("2026-08-12"),
                        T0))
                .getShipmentId();
    }

    @Test
    @DisplayName("an event published to Kafka is normalized, persisted, and updates the shipment")
    void consumesAndPersistsAPublishedEvent() {
        UUID eventId = UUID.randomUUID();

        publish(eventId, "SP_OFD", 5, T0.plusSeconds(3600));

        TrackingEvent stored = awaitEvent(eventId);
        assertThat(stored.getCarrierEventType()).isEqualTo("SP_OFD");
        assertThat(stored.getNormalizedEventType()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
        assertThat(stored.getShipmentId()).isEqualTo(shipmentId);
        assertThat(stored.getSequenceNumber()).isEqualTo(5L);
        assertThat(stored.getLocation()).isEqualTo("Ashgrove");
        assertThat(stored.getCorrelationId()).isEqualTo("corr-kafka-1");
        assertThat(stored.getEventTime()).isEqualTo(T0.plusSeconds(3600));
        assertThat(stored.getProcessingStatus()).isEqualTo(EventProcessingStatus.APPLIED);

        assertThat(shipmentRepository.findById(shipmentId).orElseThrow().getCurrentStatus())
                .isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    @DisplayName("a sequence of published events walks the shipment to DELIVERED in order")
    void consumesAnOrderedSequence() {
        List<String> journey = List.of("SP_CREATED", "SP_PICKUP", "SP_TRANSIT", "SP_OFD",
                "SP_DELIVERED");

        for (int i = 0; i < journey.size(); i++) {
            publish(UUID.randomUUID(), journey.get(i), i + 1, T0.plusSeconds((i + 1) * 3600L));
        }

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(trackingEventRepository.count()).isEqualTo(journey.size()));

        assertThat(shipmentRepository.findById(shipmentId).orElseThrow().getCurrentStatus())
                .isEqualTo(ShipmentStatus.DELIVERED);
    }

    @Test
    @DisplayName("a message the consumer cannot process does not stall the partition")
    void aFailingRecordDoesNotBlockLaterRecords() {
        // Unknown carrier code: normalization throws, the container's default error handler gives
        // up after its retries and moves past the record. Stage 3 replaces that with a dead letter
        // publication; what matters here is that the consumer keeps running.
        publish(UUID.randomUUID(), "SP_TELEPORTED", 1, T0.plusSeconds(60));

        UUID goodEventId = UUID.randomUUID();
        publish(goodEventId, "SP_PICKUP", 2, T0.plusSeconds(120));

        TrackingEvent stored = awaitEvent(goodEventId);
        assertThat(stored.getNormalizedEventType()).isEqualTo(ShipmentStatus.PICKED_UP);

        // The bad event was never stored.
        assertThat(trackingEventRepository.count()).isEqualTo(1);
    }

    private TrackingEvent awaitEvent(UUID eventId) {
        await().atMost(TIMEOUT)
                .pollInterval(Duration.ofMillis(200))
                .until(() -> trackingEventRepository.findByEventId(eventId).isPresent());
        return trackingEventRepository.findByEventId(eventId).orElseThrow();
    }

    /**
     * Keyed by shipment id, exactly as the simulator does, so partition assignment in the test
     * matches production.
     */
    private void publish(UUID eventId, String eventType, long sequenceNumber, Instant eventTime) {
        String payload = """
                {
                  "eventId": "%s",
                  "schemaVersion": 1,
                  "shipmentId": "%s",
                  "trackingNumber": "SP-KAFKA-1",
                  "carrierCode": "SWIFTPOST",
                  "eventType": "%s",
                  "eventTime": "%s",
                  "sequenceNumber": %d,
                  "location": "Ashgrove",
                  "description": "Scan recorded",
                  "correlationId": "corr-kafka-1"
                }""".formatted(eventId, shipmentId, eventType, eventTime, sequenceNumber);

        kafkaTemplate.send(topic, shipmentId.toString(), payload).join();
    }
}
