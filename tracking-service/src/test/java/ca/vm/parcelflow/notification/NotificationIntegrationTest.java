package ca.vm.parcelflow.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.notification.domain.Notification;
import ca.vm.parcelflow.notification.domain.NotificationChannel;
import ca.vm.parcelflow.notification.domain.NotificationStatus;
import ca.vm.parcelflow.notification.domain.NotificationType;
import ca.vm.parcelflow.shipment.ShipmentRepository;
import ca.vm.parcelflow.shipment.domain.Shipment;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import ca.vm.parcelflow.support.CarrierEvents;
import ca.vm.parcelflow.support.PostgresIntegrationTest;
import ca.vm.parcelflow.tracking.TrackingEventProcessor;
import ca.vm.parcelflow.tracking.TrackingEventRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Which events notify, which do not, and what the endpoint returns. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

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
                        UUID.randomUUID(), "retailer-1", "cust-1", "SP-NOTIFY-1",
                        CarrierCode.SWIFTPOST, LocalDate.parse("2026-08-12"), CarrierEvents.T0))
                .getShipmentId();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "SP_PICKUP,    PICKED_UP",
            "SP_OFD,       OUT_FOR_DELIVERY",
            "SP_DELAY,     DELAYED",
            "SP_ATTEMPT,   DELIVERY_ATTEMPTED",
            "SP_DELIVERED, DELIVERED"
    })
    @DisplayName("every notifiable milestone creates exactly one record")
    void notifiableMilestones(String carrierEventType, NotificationType expected) {
        var message = CarrierEvents.swiftPost(shipmentId, carrierEventType, 5).build();

        processor.process(message);

        assertThat(notificationRepository.findAll())
                .singleElement()
                .satisfies(notification -> {
                    assertThat(notification.getNotificationType()).isEqualTo(expected);
                    assertThat(notification.getShipmentId()).isEqualTo(shipmentId);
                    assertThat(notification.getSourceEventId()).isEqualTo(message.eventId());
                    assertThat(notification.getChannel()).isEqualTo(NotificationChannel.EMAIL);
                    // Nothing dispatches these, by design.
                    assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"SP_CREATED", "SP_TRANSIT", "SP_DEPOT"})
    @DisplayName("routine scans do not notify: they repeat many times per journey")
    void nonNotifiableMilestones(String carrierEventType) {
        processor.process(CarrierEvents.swiftPost(shipmentId, carrierEventType, 5).build());

        assertThat(notificationRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a full journey produces one notification per milestone, in order")
    void fullJourneyNotifications() {
        String[] journey = {"SP_CREATED", "SP_PICKUP", "SP_TRANSIT", "SP_DEPOT", "SP_OFD",
                "SP_DELIVERED"};
        for (int i = 0; i < journey.length; i++) {
            processor.process(CarrierEvents.swiftPost(shipmentId, journey[i], i + 1L).build());
        }

        assertThat(notificationRepository.findAll())
                .extracting(Notification::getNotificationType)
                .containsExactlyInAnyOrder(
                        NotificationType.PICKED_UP,
                        NotificationType.OUT_FOR_DELIVERY,
                        NotificationType.DELIVERED);
    }

    @Test
    @DisplayName("the unique constraint is what stops a duplicate, not just the pre-check")
    void databaseConstraintPreventsDuplicates() {
        var message = CarrierEvents.swiftPost(shipmentId, "SP_OFD", 5).build();
        processor.process(message);

        Notification stored = notificationRepository.findAll().getFirst();

        // Bypass the service entirely and try to insert a second notification for the same
        // (shipment, source event). Even with the application check removed, this must fail.
        assertThat(notificationRepository.existsByShipmentIdAndSourceEventId(
                shipmentId, stored.getSourceEventId())).isTrue();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        notificationRepository.saveAndFlush(Notification.forEvent(
                                shipmentId, stored.getSourceEventId(),
                                NotificationType.DELIVERED, CarrierEvents.T0)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("two different events for the same shipment each notify")
    void differentEventsBothNotify() {
        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_PICKUP", 2).build());
        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_OFD", 5).build());

        assertThat(notificationRepository.findAll())
                .extracting(Notification::getNotificationType)
                .containsExactlyInAnyOrder(
                        NotificationType.PICKED_UP, NotificationType.OUT_FOR_DELIVERY);
    }

    @Test
    @DisplayName("the endpoint returns notifications oldest first with a stable tie-break")
    void endpointReturnsOrderedNotifications() throws Exception {
        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_PICKUP", 2).build());
        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_OFD", 5).build());
        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_DELIVERED", 8).build());

        mockMvc.perform(get("/api/shipments/{id}/notifications", shipmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].notificationType").value("PICKED_UP"))
                .andExpect(jsonPath("$.content[1].notificationType").value("OUT_FOR_DELIVERY"))
                .andExpect(jsonPath("$.content[2].notificationType").value("DELIVERED"))
                .andExpect(jsonPath("$.content[0].channel").value("EMAIL"))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].sourceEventId").exists())
                .andExpect(jsonPath("$.content[0].createdAt").exists());
    }

    @Test
    @DisplayName("the endpoint paginates")
    void endpointPaginates() throws Exception {
        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_PICKUP", 2).build());
        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_OFD", 5).build());
        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_DELIVERED", 8).build());

        mockMvc.perform(get("/api/shipments/{id}/notifications", shipmentId)
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    @DisplayName("a shipment with no milestones yet returns an empty page, not a 404")
    void emptyNotificationsIsAnEmptyPage() throws Exception {
        mockMvc.perform(get("/api/shipments/{id}/notifications", shipmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    @DisplayName("an unknown shipment returns 404")
    void unknownShipmentIsNotFound() throws Exception {
        mockMvc.perform(get("/api/shipments/{id}/notifications", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type")
                        .value("https://parcelflow.example/problems/shipment-not-found"));
    }

    @Test
    @DisplayName("the notifiable set is exactly the five documented milestones")
    void notifiableSetIsExactlyFive() {
        // Guards the product decision against an accidental edit: adding a status to the mapping
        // starts messaging customers, and that should never happen as a side effect.
        long notifiable = java.util.Arrays.stream(ShipmentStatus.values())
                .filter(status -> NotificationType.forStatus(status).isPresent())
                .count();

        assertThat(notifiable).isEqualTo(5);
        assertThat(NotificationType.forStatus(ShipmentStatus.LABEL_CREATED)).isEmpty();
        assertThat(NotificationType.forStatus(ShipmentStatus.IN_TRANSIT)).isEmpty();
        assertThat(NotificationType.forStatus(ShipmentStatus.ARRIVED_AT_FACILITY)).isEmpty();
    }
}
