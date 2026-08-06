package ca.vm.parcelflow.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.shipment.domain.Shipment;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import ca.vm.parcelflow.support.CarrierEvents;
import ca.vm.parcelflow.support.PostgresIntegrationTest;
import ca.vm.parcelflow.tracking.TrackingEventProcessingResult;
import ca.vm.parcelflow.tracking.TrackingEventProcessor;
import ca.vm.parcelflow.tracking.TrackingEventRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The service with Redis pointed at a port where nothing is listening.
 *
 * <p>This is the test behind the claim that correctness does not depend on Redis. Rather than
 * stopping a shared container mid-suite — which would break every other test in the JVM — it
 * enables the cache and aims it at a dead port, which is indistinguishable from an outage as far as
 * the application is concerned.
 *
 * <p>Every operation here must still produce the right answer. Reads fall through to PostgreSQL,
 * writes commit, and an eviction that cannot reach Redis does not turn a successfully persisted
 * tracking event into a failure.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "parcelflow.cache.enabled=true",
        // Nothing listens here. Short timeouts keep the test fast; in production they keep a
        // request thread from parking on an unreachable cache.
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6399",
        "spring.data.redis.timeout=200ms",
        "spring.data.redis.connect-timeout=200ms",
        "management.health.redis.enabled=false"
})
class ShipmentCacheUnavailableIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private ShipmentService shipmentService;

    @Autowired
    private TrackingEventProcessor processor;

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
                        UUID.randomUUID(), "retailer-1", "cust-1", "SP-NOREDIS-1",
                        CarrierCode.SWIFTPOST, LocalDate.parse("2026-08-12"), CarrierEvents.T0))
                .getShipmentId();
    }

    @Test
    @DisplayName("a read still returns the right answer when the cache is unreachable")
    void readFallsThroughToPostgres() {
        assertThat(shipmentService.getShipmentTracking(shipmentId).currentStatus())
                .isEqualTo(ShipmentStatus.LABEL_CREATED);

        // And again — a failed cache write must not poison the second call either.
        assertThat(shipmentService.getShipmentTracking(shipmentId).trackingNumber())
                .isEqualTo("SP-NOREDIS-1");
    }

    @Test
    @DisplayName("a tracking event still commits when the eviction cannot reach Redis")
    void eventProcessingSurvivesCacheOutage() {
        TrackingEventProcessingResult result =
                processor.process(CarrierEvents.swiftPost(shipmentId, "SP_OFD", 5).build());

        // The eviction on AFTER_COMMIT fails and is swallowed. Everything that matters still
        // happened: this is the assertion that a cache outage cannot corrupt PostgreSQL.
        assertThat(result.advancedShipment()).isTrue();
        assertThat(trackingEventRepository.findByEventId(result.eventId())).isPresent();
        assertThat(shipmentRepository.findById(shipmentId).orElseThrow().getCurrentStatus())
                .isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    @DisplayName("a whole journey processes normally with no cache at all")
    void fullJourneySurvivesCacheOutage() {
        String[] journey = {"SP_CREATED", "SP_PICKUP", "SP_TRANSIT", "SP_OFD", "SP_DELIVERED"};
        for (int i = 0; i < journey.length; i++) {
            processor.process(CarrierEvents.swiftPost(shipmentId, journey[i], i + 1L).build());
        }

        assertThat(trackingEventRepository.count()).isEqualTo(journey.length);
        assertThat(shipmentService.getShipmentTracking(shipmentId).currentStatus())
                .isEqualTo(ShipmentStatus.DELIVERED);
    }
}
