package ca.vm.parcelflow.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.notification.NotificationRepository;
import ca.vm.parcelflow.notification.domain.Notification;
import ca.vm.parcelflow.notification.domain.NotificationType;
import ca.vm.parcelflow.shipment.ShipmentRepository;
import ca.vm.parcelflow.shipment.domain.Shipment;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import ca.vm.parcelflow.support.CarrierEvents;
import ca.vm.parcelflow.support.PostgresIntegrationTest;
import ca.vm.parcelflow.tracking.domain.EventProcessingStatus;
import ca.vm.parcelflow.tracking.domain.TrackingEvent;
import ca.vm.parcelflow.tracking.messaging.CarrierTrackingEventMessage;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Duplicate delivery and out-of-order arrival, against a real database.
 *
 * <p>Driven through {@link TrackingEventProcessor} rather than Kafka: the guarantees under test are
 * properties of the processing path and the database constraints, not of the transport.
 */
@SpringBootTest
@ActiveProfiles("test")
class IdempotencyAndOrderingIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private TrackingEventProcessor processor;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private TrackingEventRepository trackingEventRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    private UUID shipmentId;

    @BeforeEach
    void registerShipment() {
        notificationRepository.deleteAll();
        trackingEventRepository.deleteAll();
        shipmentRepository.deleteAll();
        shipmentId = shipmentRepository.saveAndFlush(Shipment.register(
                        UUID.randomUUID(), "retailer-1", "cust-1", "SP-IDEM-1",
                        CarrierCode.SWIFTPOST, LocalDate.parse("2026-08-12"), CarrierEvents.T0))
                .getShipmentId();
    }

    @Nested
    @DisplayName("duplicate delivery")
    class Duplicates {

        @Test
        @DisplayName("the same event delivered three times stores one row and applies once")
        void repeatedDeliveryIsANoOp() {
            var message = CarrierEvents.swiftPost(shipmentId, "SP_PICKUP", 2).build();

            var first = processor.process(message);
            var second = processor.process(message);
            var third = processor.process(message);

            assertThat(first.duplicate()).isFalse();
            assertThat(first.advancedShipment()).isTrue();

            // A duplicate is a successful no-op, not a failure. Nothing throws.
            assertThat(second.duplicate()).isTrue();
            assertThat(third.duplicate()).isTrue();
            assertThat(second.advancedShipment()).isFalse();

            assertThat(trackingEventRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("a redelivery does not bump the shipment version, so it cannot lose an update")
        void redeliveryDoesNotTouchTheShipment() {
            var message = CarrierEvents.swiftPost(shipmentId, "SP_PICKUP", 2).build();
            processor.process(message);
            long versionAfterFirst = shipmentRepository.findById(shipmentId).orElseThrow().getVersion();

            processor.process(message);

            Shipment shipment = shipmentRepository.findById(shipmentId).orElseThrow();
            assertThat(shipment.getVersion()).isEqualTo(versionAfterFirst);
            assertThat(shipment.getCurrentStatus()).isEqualTo(ShipmentStatus.PICKED_UP);
        }

        @Test
        @DisplayName("a redelivery does not create a second notification")
        void redeliveryDoesNotDuplicateNotifications() {
            var message = CarrierEvents.swiftPost(shipmentId, "SP_OFD", 5).build();

            processor.process(message);
            processor.process(message);
            processor.process(message);

            List<Notification> notifications = notificationRepository.findAll();
            assertThat(notifications).hasSize(1);
            assertThat(notifications.getFirst().getNotificationType())
                    .isEqualTo(NotificationType.OUT_FOR_DELIVERY);
            assertThat(notifications.getFirst().getSourceEventId()).isEqualTo(message.eventId());
        }

        @Test
        @DisplayName("the duplicate result reports the original outcome, not a new decision")
        void duplicateReportsTheStoredOutcome() {
            processor.process(CarrierEvents.swiftPost(shipmentId, "SP_OFD", 5).build());
            var late = CarrierEvents.swiftPost(shipmentId, "SP_TRANSIT", 3).build();
            processor.process(late);

            var replay = processor.process(late);

            assertThat(replay.duplicate()).isTrue();
            assertThat(replay.processingStatus()).isEqualTo(EventProcessingStatus.SUPERSEDED);
            // The shipment status reported is the current one, which is what a caller asking
            // "where is this parcel" needs.
            assertThat(replay.shipmentStatusAfterProcessing())
                    .isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
        }

        @Test
        @DisplayName("two different events describing the same scan are not duplicates")
        void distinctEventIdsAreDistinctEvents() {
            // Same sequence, same time, different event id: a carrier correction, not a redelivery.
            var original = CarrierEvents.swiftPost(shipmentId, "SP_OFD", 5).build();
            var correction = new CarrierTrackingEventMessage(
                    UUID.randomUUID(), 1, shipmentId, original.trackingNumber(),
                    original.carrierCode(), "SP_DELAY", original.eventTime(),
                    original.sequenceNumber(), original.location(), original.description(),
                    original.correlationId());

            processor.process(original);
            var result = processor.process(correction);

            assertThat(result.duplicate()).isFalse();
            assertThat(trackingEventRepository.count()).isEqualTo(2);
            // Last received wins the final tie-break, so the correction takes effect.
            assertThat(result.advancedShipment()).isTrue();
            assertThat(shipmentRepository.findById(shipmentId).orElseThrow().getCurrentStatus())
                    .isEqualTo(ShipmentStatus.DELAYED);
        }
    }

    @Nested
    @DisplayName("out-of-order arrival")
    class OutOfOrder {

        @Test
        @DisplayName("an older sequence arriving later is kept in history but does not rewind status")
        void olderSequenceDoesNotRewind() {
            var newer = CarrierEvents.swiftPost(shipmentId, "SP_OFD", 50).build();
            var older = CarrierEvents.swiftPost(shipmentId, "SP_TRANSIT", 40).build();

            processor.process(newer);
            var result = processor.process(older);

            assertThat(result.processingStatus()).isEqualTo(EventProcessingStatus.SUPERSEDED);

            Shipment shipment = shipmentRepository.findById(shipmentId).orElseThrow();
            assertThat(shipment.getCurrentStatus()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
            assertThat(shipment.getLastSequenceNumber()).isEqualTo(50L);
            // lastEventTime must not move backwards either.
            assertThat(shipment.getLastEventTime()).isEqualTo(newer.eventTime());

            // Both events are in history.
            assertThat(trackingEventRepository.count()).isEqualTo(2);
            assertThat(trackingEventRepository.findByEventId(older.eventId()))
                    .get()
                    .extracting(TrackingEvent::getProcessingStatus)
                    .isEqualTo(EventProcessingStatus.SUPERSEDED);
        }

        @Test
        @DisplayName("a stale milestone does not notify the customer")
        void staleMilestoneDoesNotNotify() {
            processor.process(CarrierEvents.swiftPost(shipmentId, "SP_DELIVERED", 50).build());
            notificationRepository.deleteAll();

            // OUT_FOR_DELIVERY is a notifiable milestone, but this one is stale. Telling a customer
            // their delivered parcel is out for delivery is worse than saying nothing.
            processor.process(CarrierEvents.swiftPost(shipmentId, "SP_OFD", 40).build());

            assertThat(notificationRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("the same sequence with an older event time does not advance")
        void sameSequenceOlderEventTime() {
            var applied = CarrierEvents.swiftPost(shipmentId, "SP_OFD", 5)
                    .eventTime(CarrierEvents.T0.plusSeconds(600)).build();
            processor.process(applied);

            var result = processor.process(CarrierEvents.swiftPost(shipmentId, "SP_TRANSIT", 5)
                    .eventTime(CarrierEvents.T0.plusSeconds(300)).build());

            assertThat(result.processingStatus()).isEqualTo(EventProcessingStatus.SUPERSEDED);
            assertThat(shipmentRepository.findById(shipmentId).orElseThrow().getCurrentStatus())
                    .isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
        }

        @Test
        @DisplayName("a newer sequence wins even when the carrier's timestamp is older")
        void newerSequenceOlderTimestamp() {
            processor.process(CarrierEvents.swiftPost(shipmentId, "SP_TRANSIT", 4)
                    .eventTime(CarrierEvents.T0.plusSeconds(600)).build());

            // The scanner clock was behind. Sequence is the better witness.
            var result = processor.process(CarrierEvents.swiftPost(shipmentId, "SP_OFD", 5)
                    .eventTime(CarrierEvents.T0.plusSeconds(300)).build());

            assertThat(result.advancedShipment()).isTrue();
            assertThat(shipmentRepository.findById(shipmentId).orElseThrow().getCurrentStatus())
                    .isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
        }

        @Test
        @DisplayName("a whole journey delivered backwards ends in the right state with full history")
        void entireJourneyReversed() {
            String[] journey = {"SP_CREATED", "SP_PICKUP", "SP_TRANSIT", "SP_DEPOT", "SP_OFD"};

            // Delivered in reverse: the highest sequence arrives first and every later event loses.
            for (int i = journey.length - 1; i >= 0; i--) {
                processor.process(CarrierEvents.swiftPost(shipmentId, journey[i], i + 1L).build());
            }

            Shipment shipment = shipmentRepository.findById(shipmentId).orElseThrow();
            assertThat(shipment.getCurrentStatus()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
            assertThat(shipment.getLastSequenceNumber()).isEqualTo(5L);
            assertThat(trackingEventRepository.count()).isEqualTo(journey.length);

            // Exactly one event applied — the first one delivered. The rest are history.
            assertThat(trackingEventRepository.findAll())
                    .filteredOn(e -> e.getProcessingStatus() == EventProcessingStatus.APPLIED)
                    .hasSize(1);

            // Only that one produced a notification.
            assertThat(notificationRepository.findAll())
                    .extracting(Notification::getNotificationType)
                    .containsExactly(NotificationType.OUT_FOR_DELIVERY);
        }

        @Test
        @DisplayName("a delivered parcel ignores a late in-transit scan entirely")
        void deliveredParcelIgnoresLateScan() {
            processor.process(CarrierEvents.swiftPost(shipmentId, "SP_DELIVERED", 8).build());

            var result = processor.process(
                    CarrierEvents.swiftPost(shipmentId, "SP_TRANSIT", 99).build());

            assertThat(result.processingStatus()).isEqualTo(EventProcessingStatus.SUPERSEDED);
            Shipment shipment = shipmentRepository.findById(shipmentId).orElseThrow();
            assertThat(shipment.getCurrentStatus()).isEqualTo(ShipmentStatus.DELIVERED);
            assertThat(shipment.getLastSequenceNumber()).isEqualTo(8L);
            // Still recorded, so the anomaly is visible to whoever investigates it.
            assertThat(trackingEventRepository.count()).isEqualTo(2);
        }
    }
}
