package ca.vm.parcelflow.tracking.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.shipment.ShipmentRepository;
import ca.vm.parcelflow.shipment.domain.Shipment;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import ca.vm.parcelflow.support.KafkaIntegrationTest;
import ca.vm.parcelflow.tracking.TrackingEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The load-testing publish endpoint, enabled.
 *
 * <p>Worth a test because the k6 scenario depends on it end to end, and a performance number
 * produced by a harness nobody verified is worse than no number: if this endpoint quietly dropped
 * records, or keyed them wrongly so a parcel's events scattered across partitions, the load test
 * would still report a throughput and the report would be fiction.
 *
 * <p>Asserting that the event reaches PostgreSQL is what proves the events travel the real path —
 * broker, deserializer, consumer, normalizer, transaction — rather than a shortcut that would make
 * the measurement meaningless.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "parcelflow.load-testing.enabled=true")
class CarrierEventLoadControllerIntegrationTest extends KafkaIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Instant T0 = Instant.parse("2026-08-01T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private TrackingEventRepository trackingEventRepository;

    private UUID shipmentId;

    @BeforeEach
    void registerShipment() {
        trackingEventRepository.deleteAll();
        shipmentRepository.deleteAll();
        shipmentId = shipmentRepository.saveAndFlush(Shipment.register(
                        UUID.randomUUID(), "retailer-load", "cust-load", "SP-LOAD-1",
                        CarrierCode.SWIFTPOST, LocalDate.parse("2026-08-12"), T0))
                .getShipmentId();
    }

    @Test
    @DisplayName("a posted event reaches the consumer and updates the shipment")
    void publishesASingleEvent() throws Exception {
        UUID eventId = UUID.randomUUID();

        mockMvc.perform(post("/internal/load/carrier-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event(eventId, "SP_OFD", 5)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.published").value(1));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(trackingEventRepository.findByEventId(eventId)).isPresent());

        assertThat(shipmentRepository.findById(shipmentId).orElseThrow().getCurrentStatus())
                .isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    @DisplayName("a posted array publishes every event in one request")
    void publishesABatch() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        mockMvc.perform(post("/internal/load/carrier-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[" + event(first, "SP_PICKUP", 1) + ","
                                + event(second, "SP_TRANSIT", 2) + "]"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.published").value(2));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(trackingEventRepository.findByEventId(first)).isPresent();
            assertThat(trackingEventRepository.findByEventId(second)).isPresent();
        });
    }

    @Test
    @DisplayName("an invalid payload is published as written, so the DLT path can be load tested")
    void publishesInvalidPayloadsUnchanged() throws Exception {
        // The endpoint does not validate. A load test that could not inject a malformed event
        // could not measure what the failure path costs.
        mockMvc.perform(post("/internal/load/carrier-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"%s\",\"schemaVersion\":99,\"shipmentId\":\"%s\"}"
                                .formatted(UUID.randomUUID(), shipmentId)))
                .andExpect(status().isAccepted());
    }

    private String event(UUID eventId, String eventType, long sequence) {
        return """
                {
                  "eventId": "%s",
                  "schemaVersion": 1,
                  "shipmentId": "%s",
                  "trackingNumber": "SP-LOAD-1",
                  "carrierCode": "SWIFTPOST",
                  "eventType": "%s",
                  "eventTime": "%s",
                  "sequenceNumber": %d,
                  "location": "Rivermouth",
                  "description": "Load test scan",
                  "correlationId": "load-test-%s"
                }
                """.formatted(eventId, shipmentId, eventType,
                T0.plusSeconds(sequence * 3600), sequence, sequence);
    }
}
