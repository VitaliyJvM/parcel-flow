package ca.vm.parcelflow.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.carrier.normalization.UnknownCarrierEventTypeException;
import ca.vm.parcelflow.carrier.normalization.UnsupportedCarrierException;
import ca.vm.parcelflow.shipment.ShipmentNotFoundException;
import ca.vm.parcelflow.shipment.ShipmentRepository;
import ca.vm.parcelflow.shipment.domain.Shipment;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import ca.vm.parcelflow.support.CarrierEvents;
import ca.vm.parcelflow.support.PostgresIntegrationTest;
import ca.vm.parcelflow.tracking.domain.EventProcessingStatus;
import ca.vm.parcelflow.tracking.domain.TrackingEvent;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The processing pipeline against a real database, driven directly rather than through Kafka.
 *
 * <p>Bypassing the broker is the point: these assertions are about normalization, persistence,
 * status updates and error classification, none of which involve transport. The Kafka path gets its
 * own test.
 */
@SpringBootTest
@ActiveProfiles("test")
class TrackingEventProcessorIntegrationTest extends PostgresIntegrationTest {

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
        shipmentId = saveShipment(CarrierCode.SWIFTPOST, "SP-PROC-1");
    }

    @Test
    @DisplayName("a valid event is stored with both the raw and the normalized event type")
    void storesRawAndNormalizedEventType() {
        var message = CarrierEvents.swiftPost(shipmentId, "SP_OFD", 5)
                .location("Ashgrove")
                .description("Out for delivery")
                .correlationId("corr-42")
                .build();

        TrackingEventProcessingResult result = processor.process(message);

        assertThat(result.processingStatus()).isEqualTo(EventProcessingStatus.APPLIED);
        assertThat(result.normalizedEventType()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
        assertThat(result.advancedShipment()).isTrue();

        TrackingEvent stored = trackingEventRepository.findByEventId(message.eventId()).orElseThrow();
        assertThat(stored.getCarrierEventType()).isEqualTo("SP_OFD");
        assertThat(stored.getNormalizedEventType()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
        assertThat(stored.getShipmentId()).isEqualTo(shipmentId);
        assertThat(stored.getTrackingNumber()).isEqualTo("SP-TEST-1");
        assertThat(stored.getCarrierCode()).isEqualTo(CarrierCode.SWIFTPOST);
        assertThat(stored.getSequenceNumber()).isEqualTo(5L);
        assertThat(stored.getLocation()).isEqualTo("Ashgrove");
        assertThat(stored.getDescription()).isEqualTo("Out for delivery");
        assertThat(stored.getCorrelationId()).isEqualTo("corr-42");
        assertThat(stored.getEventTime()).isEqualTo(message.eventTime());
        assertThat(stored.getReceivedAt()).isNotNull();
        assertThat(stored.getId()).isNotNull();
    }

    @Test
    @DisplayName("processing advances the shipment's status, last event time and sequence")
    void advancesShipmentState() {
        var message = CarrierEvents.swiftPost(shipmentId, "SP_PICKUP", 2).build();

        processor.process(message);

        Shipment shipment = shipmentRepository.findById(shipmentId).orElseThrow();
        assertThat(shipment.getCurrentStatus()).isEqualTo(ShipmentStatus.PICKED_UP);
        assertThat(shipment.getLastEventTime()).isEqualTo(message.eventTime());
        assertThat(shipment.getLastSequenceNumber()).isEqualTo(2L);
        assertThat(shipment.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a full carrier journey walks the shipment to DELIVERED")
    void processesAFullJourney() {
        String[] journey = {"SP_CREATED", "SP_PICKUP", "SP_TRANSIT", "SP_DEPOT", "SP_OFD",
                "SP_DELIVERED"};
        for (int i = 0; i < journey.length; i++) {
            processor.process(CarrierEvents.swiftPost(shipmentId, journey[i], i + 1L).build());
        }

        assertThat(shipmentRepository.findById(shipmentId).orElseThrow().getCurrentStatus())
                .isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(trackingEventRepository.count()).isEqualTo(journey.length);
    }

    @Test
    @DisplayName("an event that loses the ordering comparison is stored as SUPERSEDED, not dropped")
    void supersededEventIsStillRecorded() {
        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_OFD", 5).build());

        var late = CarrierEvents.swiftPost(shipmentId, "SP_TRANSIT", 3).build();
        TrackingEventProcessingResult result = processor.process(late);

        assertThat(result.processingStatus()).isEqualTo(EventProcessingStatus.SUPERSEDED);
        assertThat(result.advancedShipment()).isFalse();

        // Still in history, with its own normalized reading.
        TrackingEvent stored = trackingEventRepository.findByEventId(late.eventId()).orElseThrow();
        assertThat(stored.getNormalizedEventType()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(stored.getProcessingStatus()).isEqualTo(EventProcessingStatus.SUPERSEDED);

        // But the shipment did not rewind.
        assertThat(shipmentRepository.findById(shipmentId).orElseThrow().getCurrentStatus())
                .isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    @DisplayName("Pacifica's vocabulary reaches the same normalized statuses")
    void normalizesASecondCarrier() {
        UUID pacificaShipment = saveShipment(CarrierCode.PACIFICA, "PC-PROC-1");

        processor.process(CarrierEvents.pacifica(pacificaShipment, "COURIER_ROUTE", 4).build());

        assertThat(shipmentRepository.findById(pacificaShipment).orElseThrow().getCurrentStatus())
                .isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    @DisplayName("an event for an unknown shipment is rejected and stores nothing")
    void rejectsUnknownShipment() {
        var message = CarrierEvents.swiftPost(UUID.randomUUID(), "SP_TRANSIT", 1).build();

        assertThatThrownBy(() -> processor.process(message))
                .isInstanceOf(ShipmentNotFoundException.class);

        assertThat(trackingEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("an event whose carrier disagrees with the shipment is rejected")
    void rejectsCarrierMismatch() {
        var message = CarrierEvents.pacifica(shipmentId, "MOVING", 1).build();

        assertThatThrownBy(() -> processor.process(message))
                .isInstanceOf(CarrierMismatchException.class)
                .hasMessageContaining("SWIFTPOST")
                .hasMessageContaining("PACIFICA");

        assertThat(trackingEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("an unrecognised carrier code is rejected before anything is written")
    void rejectsUnknownCarrierEventType() {
        var message = CarrierEvents.swiftPost(shipmentId, "SP_TELEPORTED", 1).build();

        assertThatThrownBy(() -> processor.process(message))
                .isInstanceOf(UnknownCarrierEventTypeException.class);

        assertThat(trackingEventRepository.count()).isZero();
        assertThat(shipmentRepository.findById(shipmentId).orElseThrow().getCurrentStatus())
                .isEqualTo(ShipmentStatus.LABEL_CREATED);
    }

    @Test
    @DisplayName("a carrier with no registered normalizer is rejected as unsupported")
    void rejectsUnsupportedCarrier() {
        UUID nordexShipment = saveShipment(CarrierCode.NORDEX, "NX-PROC-1");
        var message = CarrierEvents.swiftPost(nordexShipment, "ANY_CODE", 1)
                .carrierCode(CarrierCode.NORDEX)
                .build();

        assertThatThrownBy(() -> processor.process(message))
                .isInstanceOf(UnsupportedCarrierException.class);

        assertThat(trackingEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("an unsupported schema version is rejected as permanently invalid")
    void rejectsUnsupportedSchemaVersion() {
        var message = CarrierEvents.swiftPost(shipmentId, "SP_TRANSIT", 1)
                .schemaVersion(99)
                .build();

        assertThatThrownBy(() -> processor.process(message))
                .isInstanceOf(InvalidCarrierEventException.class)
                .hasMessageContaining("schema version");

        assertThat(trackingEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("a missing required field is reported with the field name")
    void rejectsMissingRequiredField() {
        var message = CarrierEvents.swiftPost(shipmentId, "SP_TRANSIT", 1)
                .correlationId("  ")
                .build();

        assertThatThrownBy(() -> processor.process(message))
                .isInstanceOf(InvalidCarrierEventException.class)
                .hasMessageContaining("correlationId");
    }

    @Test
    @DisplayName("a non-positive sequence number is rejected: it would break ordering comparisons")
    void rejectsNonPositiveSequenceNumber() {
        var message = CarrierEvents.swiftPost(shipmentId, "SP_TRANSIT", 1)
                .sequenceNumber(0L)
                .build();

        assertThatThrownBy(() -> processor.process(message))
                .isInstanceOf(InvalidCarrierEventException.class)
                .hasMessageContaining("sequenceNumber");
    }

    @Test
    @DisplayName("optional location and description may be absent")
    void acceptsAbsentOptionalFields() {
        var message = CarrierEvents.swiftPost(shipmentId, "SP_TRANSIT", 1)
                .location(null)
                .description(null)
                .build();

        processor.process(message);

        TrackingEvent stored = trackingEventRepository.findByEventId(message.eventId()).orElseThrow();
        assertThat(stored.getLocation()).isNull();
        assertThat(stored.getDescription()).isNull();
    }

    private UUID saveShipment(CarrierCode carrierCode, String trackingNumber) {
        return shipmentRepository.saveAndFlush(Shipment.register(
                        UUID.randomUUID(),
                        "retailer-1",
                        "cust-1",
                        trackingNumber,
                        carrierCode,
                        LocalDate.parse("2026-08-12"),
                        CarrierEvents.T0))
                .getShipmentId();
    }
}
