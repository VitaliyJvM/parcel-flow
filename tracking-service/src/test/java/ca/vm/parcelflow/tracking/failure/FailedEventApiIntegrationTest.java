package ca.vm.parcelflow.tracking.failure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.shipment.ShipmentRepository;
import ca.vm.parcelflow.shipment.domain.Shipment;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import ca.vm.parcelflow.support.CarrierEvents;
import ca.vm.parcelflow.support.PostgresIntegrationTest;
import ca.vm.parcelflow.tracking.InvalidCarrierEventException;
import ca.vm.parcelflow.tracking.TrackingEventRepository;
import ca.vm.parcelflow.carrier.normalization.UnknownCarrierEventTypeException;
import ca.vm.parcelflow.shipment.ShipmentNotFoundException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The administrative failed-event API: listing, filtering, and manual retry.
 *
 * <p>Failures are seeded through {@link FailedEventStore} rather than by pushing bad messages
 * through Kafka. The transport path is already covered by {@code DeadLetterIntegrationTest}; what
 * matters here is the operator workflow on top of the stored record, and seeding directly makes
 * each case exact instead of dependent on a broker round trip.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FailedEventApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FailedEventStore store;

    @Autowired
    private FailedEventRepository failedEventRepository;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private TrackingEventRepository trackingEventRepository;

    private UUID shipmentId;

    @BeforeEach
    void reset() {
        failedEventRepository.deleteAll();
        trackingEventRepository.deleteAll();
        shipmentRepository.deleteAll();
        shipmentId = shipmentRepository.saveAndFlush(Shipment.register(
                        UUID.randomUUID(), "retailer-1", "cust-1", "SP-ADMIN-1",
                        CarrierCode.SWIFTPOST, LocalDate.parse("2026-08-12"), CarrierEvents.T0))
                .getShipmentId();
    }

    @Test
    @DisplayName("the listing paginates and never exposes a stack trace or the payload")
    void listsFailedEvents() throws Exception {
        seedFailure(UUID.randomUUID(), new InvalidCarrierEventException("missing correlationId"));
        seedFailure(UUID.randomUUID(), new UnknownCarrierEventTypeException(
                CarrierCode.SWIFTPOST, "SP_TELEPORTED"));

        String body = mockMvc.perform(get("/api/admin/failed-events").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].failedEventId").exists())
                .andExpect(jsonPath("$.content[0].errorCategory").exists())
                .andExpect(jsonPath("$.content[0].errorType").exists())
                .andExpect(jsonPath("$.content[0].retryableAutomatically").exists())
                .andExpect(jsonPath("$.content[0].retryableManually").exists())
                .andExpect(jsonPath("$.content[0].originalTopic").exists())
                .andReturn().getResponse().getContentAsString();

        // A stack trace in an HTTP response leaks the internal class layout; the payload can carry
        // a customer reference. Neither belongs in a triage listing.
        assertThat(body).doesNotContain("\tat ").doesNotContain("payload");
    }

    @Test
    @DisplayName("the listing filters by status")
    void filtersByStatus() throws Exception {
        UUID resolvedEventId = UUID.randomUUID();
        seedFailure(resolvedEventId, new InvalidCarrierEventException("bad"));
        store.markResolved(failedEventRepository.findByEventId(resolvedEventId)
                .orElseThrow().getFailedEventId());
        seedFailure(UUID.randomUUID(), new InvalidCarrierEventException("also bad"));

        mockMvc.perform(get("/api/admin/failed-events").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("FAILED"));

        mockMvc.perform(get("/api/admin/failed-events").param("status", "RESOLVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("an unknown failed event returns 404")
    void unknownFailedEventIsNotFound() throws Exception {
        mockMvc.perform(get("/api/admin/failed-events/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type")
                        .value("https://parcelflow.example/problems/failed-event-not-found"));
    }

    @Test
    @DisplayName("retrying a permanently invalid event is refused with 409 and its category")
    void retryOfNonRetryableIsRefused() throws Exception {
        UUID eventId = UUID.randomUUID();
        seedFailure(eventId, new InvalidCarrierEventException("missing correlationId"));
        UUID failedEventId = failedEventRepository.findByEventId(eventId).orElseThrow()
                .getFailedEventId();

        mockMvc.perform(post("/api/admin/failed-events/{id}/retry", failedEventId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type")
                        .value("https://parcelflow.example/problems/failed-event-not-retryable"))
                .andExpect(jsonPath("$.errorCategory").value("VALIDATION"));

        // Refused, not consumed: the record stays FAILED and its retry count is untouched.
        FailedEvent unchanged = failedEventRepository.findByEventId(eventId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(FailedEventStatus.FAILED);
        assertThat(unchanged.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("retrying after the shipment appears succeeds and resolves the record")
    void retrySucceedsOnceTheShipmentExists() throws Exception {
        // The scenario the SHIPMENT_NOT_FOUND policy exists for: the carrier's scan arrived before
        // the retailer registered the parcel, exhausted its automatic retries, and now the parcel
        // is there.
        UUID eventId = UUID.randomUUID();
        var message = CarrierEvents.swiftPost(shipmentId, "SP_OFD", 5)
                .eventId(eventId).trackingNumber("SP-ADMIN-1").build();

        store.recordFailure(eventId, shipmentId, payloadOf(message),
                new ShipmentNotFoundException(shipmentId), "carrier-tracking-events", 0, 42L);

        UUID failedEventId = failedEventRepository.findByEventId(eventId).orElseThrow()
                .getFailedEventId();

        mockMvc.perform(post("/api/admin/failed-events/{id}/retry", failedEventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(true))
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.processingStatus").value("APPLIED"))
                .andExpect(jsonPath("$.shipmentStatus").value("OUT_FOR_DELIVERY"))
                .andExpect(jsonPath("$.duplicate").value(false));

        assertThat(failedEventRepository.findByEventId(eventId).orElseThrow().getStatus())
                .isEqualTo(FailedEventStatus.RESOLVED);
        assertThat(trackingEventRepository.findByEventId(eventId)).isPresent();
        assertThat(shipmentRepository.findById(shipmentId).orElseThrow().getCurrentStatus())
                .isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    @DisplayName("retrying an event that was already processed reports a duplicate, not a failure")
    void retryOfAlreadyProcessedEventIsIdempotent() throws Exception {
        UUID eventId = UUID.randomUUID();
        var message = CarrierEvents.swiftPost(shipmentId, "SP_OFD", 5)
                .eventId(eventId).trackingNumber("SP-ADMIN-1").build();

        store.recordFailure(eventId, shipmentId, payloadOf(message),
                new ShipmentNotFoundException(shipmentId), "carrier-tracking-events", 0, 1L);
        UUID failedEventId = failedEventRepository.findByEventId(eventId).orElseThrow()
                .getFailedEventId();

        // First retry processes it.
        mockMvc.perform(post("/api/admin/failed-events/{id}/retry", failedEventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));

        // Put it back in FAILED to simulate an operator retrying a record that has since been
        // handled by the normal pipeline, then retry again.
        store.markRetryFailed(failedEventId, new ShipmentNotFoundException(shipmentId));

        mockMvc.perform(post("/api/admin/failed-events/{id}/retry", failedEventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(true))
                .andExpect(jsonPath("$.duplicate").value(true));

        // Still exactly one history row: reprocessing is idempotent.
        assertThat(trackingEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a second concurrent retry is refused while the first holds the record")
    void concurrentRetryIsRefused() throws Exception {
        UUID eventId = UUID.randomUUID();
        seedFailure(eventId, new ShipmentNotFoundException(shipmentId));
        UUID failedEventId = failedEventRepository.findByEventId(eventId).orElseThrow()
                .getFailedEventId();

        // Claim it the way the first request would, then let a second request try.
        assertThat(store.claimForRetry(failedEventId)).isTrue();

        mockMvc.perform(post("/api/admin/failed-events/{id}/retry", failedEventId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type")
                        .value("https://parcelflow.example/problems/failed-event-retry-not-available"))
                .andExpect(jsonPath("$.status").value("RETRYING"));

        // And the claim itself is a compare-and-set: the second caller cannot take it either.
        assertThat(store.claimForRetry(failedEventId)).isFalse();
    }

    @Test
    @DisplayName("a retry that fails again increments the retry count and returns the record to FAILED")
    void failedRetryIsRecorded() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID missingShipment = UUID.randomUUID();
        var message = CarrierEvents.swiftPost(missingShipment, "SP_OFD", 5).eventId(eventId).build();

        store.recordFailure(eventId, missingShipment, payloadOf(message),
                new ShipmentNotFoundException(missingShipment), "carrier-tracking-events", 0, 7L);
        UUID failedEventId = failedEventRepository.findByEventId(eventId).orElseThrow()
                .getFailedEventId();

        // The shipment still does not exist, so reprocessing fails the same way.
        mockMvc.perform(post("/api/admin/failed-events/{id}/retry", failedEventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(false))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("SHIPMENT_NOT_FOUND")));

        FailedEvent afterRetry = failedEventRepository.findByEventId(eventId).orElseThrow();
        assertThat(afterRetry.getStatus()).isEqualTo(FailedEventStatus.FAILED);
        assertThat(afterRetry.getRetryCount()).isEqualTo(1);
        // firstFailedAt is preserved so the age of the problem stays visible.
        assertThat(afterRetry.getFirstFailedAt()).isBeforeOrEqualTo(afterRetry.getLastFailedAt());
    }

    @Test
    @DisplayName("an unparseable payload cannot be retried and does not stay stuck in RETRYING")
    void unparseablePayloadIsRefusedAndReleased() throws Exception {
        UUID eventId = UUID.randomUUID();
        store.recordFailure(eventId, null, "this is not json at all",
                new ShipmentNotFoundException(shipmentId), "carrier-tracking-events", 0, 3L);
        UUID failedEventId = failedEventRepository.findByEventId(eventId).orElseThrow()
                .getFailedEventId();

        mockMvc.perform(post("/api/admin/failed-events/{id}/retry", failedEventId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCategory").value("MALFORMED_PAYLOAD"));

        // Released rather than abandoned mid-claim: a record left in RETRYING could never be
        // retried again by anyone.
        assertThat(failedEventRepository.findByEventId(eventId).orElseThrow().getStatus())
                .isEqualTo(FailedEventStatus.FAILED);
    }

    @Test
    @DisplayName("repeated failures of one event update a single row rather than piling up")
    void repeatedFailuresUpsert() {
        UUID eventId = UUID.randomUUID();
        seedFailure(eventId, new InvalidCarrierEventException("first"));
        seedFailure(eventId, new InvalidCarrierEventException("second"));
        seedFailure(eventId, new InvalidCarrierEventException("third"));

        assertThat(failedEventRepository.count()).isEqualTo(1);
        assertThat(failedEventRepository.findByEventId(eventId).orElseThrow().getErrorMessage())
                .isEqualTo("third");
    }

    private void seedFailure(UUID eventId, Throwable failure) {
        store.recordFailure(eventId, shipmentId, "{\"eventId\":\"" + eventId + "\"}", failure,
                "carrier-tracking-events", 0, 1L);
    }

    private String payloadOf(ca.vm.parcelflow.tracking.messaging.CarrierTrackingEventMessage m) {
        return """
                {
                  "eventId": "%s",
                  "schemaVersion": 1,
                  "shipmentId": "%s",
                  "trackingNumber": "%s",
                  "carrierCode": "%s",
                  "eventType": "%s",
                  "eventTime": "%s",
                  "sequenceNumber": %d,
                  "location": "%s",
                  "description": "%s",
                  "correlationId": "%s"
                }""".formatted(m.eventId(), m.shipmentId(), m.trackingNumber(), m.carrierCode(),
                m.eventType(), m.eventTime(), m.sequenceNumber(), m.location(), m.description(),
                m.correlationId());
    }
}
