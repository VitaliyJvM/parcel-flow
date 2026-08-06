package ca.vm.parcelflow.tracking.failure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.shipment.ShipmentRepository;
import ca.vm.parcelflow.shipment.domain.Shipment;
import ca.vm.parcelflow.support.CarrierEvents;
import ca.vm.parcelflow.support.KafkaIntegrationTest;
import ca.vm.parcelflow.tracking.TrackingEventRepository;
import ca.vm.parcelflow.tracking.error.ErrorCategory;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.ActiveProfiles;

/**
 * The full failure path: a bad record is retried, gives up, is recorded in PostgreSQL, and lands on
 * the dead letter topic with the metadata an operator needs.
 *
 * <p>Uses a real broker because the thing under test is the container's error handler and the
 * republish, neither of which exists without one.
 */
@SpringBootTest
@ActiveProfiles("test")
class DeadLetterIntegrationTest extends KafkaIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private TrackingEventRepository trackingEventRepository;

    @Autowired
    private FailedEventRepository failedEventRepository;

    @Value("${parcelflow.kafka.carrier-tracking-events-topic}")
    private String topic;

    @Value("${parcelflow.kafka.carrier-tracking-events-dlt-topic}")
    private String deadLetterTopic;

    private UUID shipmentId;

    @BeforeEach
    void registerShipment() {
        failedEventRepository.deleteAll();
        trackingEventRepository.deleteAll();
        shipmentRepository.deleteAll();
        shipmentId = shipmentRepository.saveAndFlush(Shipment.register(
                        UUID.randomUUID(), "retailer-1", "cust-1", "SP-DLT-1",
                        CarrierCode.SWIFTPOST, LocalDate.parse("2026-08-12"), CarrierEvents.T0))
                .getShipmentId();
    }

    @Test
    @DisplayName("an unmappable carrier code is recorded and dead-lettered with origin metadata")
    void unknownEventTypeIsRecordedAndDeadLettered() {
        UUID eventId = UUID.randomUUID();
        publish(eventId, "SP_TELEPORTED", 1, 1);

        FailedEvent failedEvent = awaitFailedEvent(eventId);

        assertThat(failedEvent.getErrorCategory()).isEqualTo(ErrorCategory.UNKNOWN_EVENT_TYPE);
        assertThat(failedEvent.getStatus()).isEqualTo(FailedEventStatus.FAILED);
        assertThat(failedEvent.getShipmentId()).isEqualTo(shipmentId);
        assertThat(failedEvent.getOriginalTopic()).isEqualTo(topic);
        assertThat(failedEvent.getOriginalPartition()).isGreaterThanOrEqualTo(0);
        assertThat(failedEvent.getOriginalOffset()).isGreaterThanOrEqualTo(0);
        assertThat(failedEvent.getRetryCount()).isZero();
        assertThat(failedEvent.getFirstFailedAt()).isNotNull();
        assertThat(failedEvent.getLastFailedAt()).isNotNull();

        // The exception type and message are kept; a stack trace is not.
        assertThat(failedEvent.getErrorType()).contains("UnknownCarrierEventTypeException");
        assertThat(failedEvent.getErrorMessage()).contains("SP_TELEPORTED");
        assertThat(failedEvent.getErrorMessage()).doesNotContain("\tat ");

        // The payload is kept verbatim so a manual retry has something to reprocess.
        assertThat(failedEvent.getPayload()).contains(eventId.toString());

        // Nothing was written to history.
        assertThat(trackingEventRepository.count()).isZero();

        // The topic accumulates across the tests in this class, so pick out this event's record
        // rather than asserting on whatever happens to be first.
        ConsumerRecord<String, String> deadLettered = readDeadLetterRecords().stream()
                .filter(record -> record.value().contains(eventId.toString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No dead letter record found for event " + eventId));

        Map<String, String> headers = headersOf(deadLettered);
        assertThat(headers.get(KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(topic);
        assertThat(headers).containsKeys(
                KafkaHeaders.DLT_ORIGINAL_PARTITION, KafkaHeaders.DLT_ORIGINAL_OFFSET);

        // Spring records the listener wrapper as the exception and the real failure as its cause.
        // The cause is the one worth asserting on — an operator triaging the DLT needs to know it
        // was an unmappable carrier code, not that a listener method threw.
        assertThat(headers.get(KafkaHeaders.DLT_EXCEPTION_FQCN))
                .contains("ListenerExecutionFailedException");
        assertThat(headers.get(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .contains("UnknownCarrierEventTypeException");
        assertThat(headers.get(KafkaHeaders.DLT_EXCEPTION_MESSAGE)).contains("SP_TELEPORTED");
    }

    @Test
    @DisplayName("a validation failure is classified separately from an unmappable code")
    void validationFailureIsRecordedWithItsOwnCategory() {
        UUID eventId = UUID.randomUUID();
        // schemaVersion 99: structurally parseable, but not a contract this service understands.
        publish(eventId, "SP_PICKUP", 1, 99);

        FailedEvent failedEvent = awaitFailedEvent(eventId);

        assertThat(failedEvent.getErrorCategory()).isEqualTo(ErrorCategory.VALIDATION);
        assertThat(failedEvent.getErrorCategory().isRetryableAutomatically()).isFalse();
        assertThat(failedEvent.getErrorMessage()).contains("schema version");
    }

    @Test
    @DisplayName("a poison record does not stop the consumer processing later records")
    void poisonRecordDoesNotStallTheConsumer() {
        publish(UUID.randomUUID(), "SP_TELEPORTED", 1, 1);

        UUID goodEventId = UUID.randomUUID();
        publish(goodEventId, "SP_PICKUP", 2, 1);

        await().atMost(TIMEOUT).until(() ->
                trackingEventRepository.findByEventId(goodEventId).isPresent());

        assertThat(trackingEventRepository.count()).isEqualTo(1);
        assertThat(failedEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("an event for an unknown shipment is retried, then dead-lettered as retryable")
    void shipmentNotFoundIsRetriedThenDeadLettered() {
        UUID eventId = UUID.randomUUID();
        UUID unknownShipment = UUID.randomUUID();

        String payload = payload(eventId, unknownShipment, "SP_PICKUP", 1, 1);
        kafkaTemplate.send(topic, unknownShipment.toString(), payload).join();

        FailedEvent failedEvent = awaitFailedEvent(eventId);

        // Project policy: a carrier's first scan can beat the retailer's registration call, so this
        // is retried rather than rejected outright — and stays manually retryable afterwards, which
        // is what makes the admin endpoint useful once the shipment appears.
        assertThat(failedEvent.getErrorCategory()).isEqualTo(ErrorCategory.SHIPMENT_NOT_FOUND);
        assertThat(failedEvent.getErrorCategory().isRetryableAutomatically()).isTrue();
        assertThat(failedEvent.getErrorCategory().isRetryableManually()).isTrue();
    }

    private FailedEvent awaitFailedEvent(UUID eventId) {
        await().atMost(TIMEOUT)
                .pollInterval(Duration.ofMillis(200))
                .until(() -> failedEventRepository.findByEventId(eventId).isPresent());
        return failedEventRepository.findByEventId(eventId).orElseThrow();
    }

    private void publish(UUID eventId, String eventType, long sequenceNumber, int schemaVersion) {
        kafkaTemplate.send(topic, shipmentId.toString(),
                payload(eventId, shipmentId, eventType, sequenceNumber, schemaVersion)).join();
    }

    private String payload(
            UUID eventId, UUID targetShipment, String eventType, long sequenceNumber, int schemaVersion) {
        return """
                {
                  "eventId": "%s",
                  "schemaVersion": %d,
                  "shipmentId": "%s",
                  "trackingNumber": "SP-DLT-1",
                  "carrierCode": "SWIFTPOST",
                  "eventType": "%s",
                  "eventTime": "2026-08-01T12:00:00Z",
                  "sequenceNumber": %d,
                  "location": "Rivermouth",
                  "description": "Scan",
                  "correlationId": "corr-dlt"
                }""".formatted(eventId, schemaVersion, targetShipment, eventType, sequenceNumber);
    }

    /**
     * Drains the dead letter topic with a throwaway consumer group.
     *
     * <p>Polls on the calling thread against a deadline rather than through Awaitility.
     * {@code KafkaConsumer} is explicitly not thread-safe, and Awaitility evaluates its condition on
     * a separate thread — which would either trip the consumer's own concurrent-access check or,
     * worse, work by luck. {@code poll()} already blocks for its timeout, so the loop waits without
     * a sleep.
     */
    private List<ConsumerRecord<String, String>> readDeadLetterRecords() {
        Map<String, Object> config = Map.of(
                "bootstrap.servers", REDPANDA.getBootstrapServers(),
                "group.id", "dlt-assertions-" + UUID.randomUUID(),
                "auto.offset.reset", "earliest",
                "enable.auto.commit", "false");

        List<ConsumerRecord<String, String>> collected = new java.util.ArrayList<>();

        try (var consumer = new KafkaConsumer<String, String>(
                config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(deadLetterTopic));

            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (collected.isEmpty() && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
                polled.forEach(collected::add);
            }
        }

        assertThat(collected)
                .as("nothing arrived on %s within %s", deadLetterTopic, TIMEOUT)
                .isNotEmpty();
        return collected;
    }

    private Map<String, String> headersOf(ConsumerRecord<String, String> record) {
        Map<String, String> headers = new java.util.HashMap<>();
        for (Header header : record.headers()) {
            headers.put(header.key(), new String(header.value()));
        }
        return headers;
    }
}
