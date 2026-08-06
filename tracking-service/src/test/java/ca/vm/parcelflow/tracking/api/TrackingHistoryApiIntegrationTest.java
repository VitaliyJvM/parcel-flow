package ca.vm.parcelflow.tracking.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.shipment.ShipmentRepository;
import ca.vm.parcelflow.shipment.domain.Shipment;
import ca.vm.parcelflow.support.CarrierEvents;
import ca.vm.parcelflow.support.PostgresIntegrationTest;
import ca.vm.parcelflow.tracking.TrackingEventProcessor;
import ca.vm.parcelflow.tracking.TrackingEventRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The tracking history endpoint: ordering, pagination, DTO shape, and 404 semantics. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TrackingHistoryApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

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
                        UUID.randomUUID(),
                        "retailer-1",
                        "cust-1",
                        "SP-HISTORY-1",
                        CarrierCode.SWIFTPOST,
                        LocalDate.parse("2026-08-12"),
                        CarrierEvents.T0))
                .getShipmentId();
    }

    @Test
    @DisplayName("history is returned oldest first, regardless of the order events were ingested")
    void ordersChronologicallyNotByInsertion() throws Exception {
        // Ingested out of order on purpose: 5, then 1, then 3. Insertion order must not leak into
        // the response.
        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_OFD", 5).build());
        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_CREATED", 1).build());
        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_TRANSIT", 3).build());

        assertHistory()
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].carrierEventType").value("SP_CREATED"))
                .andExpect(jsonPath("$.content[1].carrierEventType").value("SP_TRANSIT"))
                .andExpect(jsonPath("$.content[2].carrierEventType").value("SP_OFD"));
    }

    @Test
    @DisplayName("events sharing an event time are tie-broken by sequence number")
    void tieBreaksBySequenceNumber() throws Exception {
        var sameTime = CarrierEvents.T0.plusSeconds(600);
        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_DEPOT", 4)
                .eventTime(sameTime).build());
        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_PICKUP", 2)
                .eventTime(sameTime).build());
        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_TRANSIT", 3)
                .eventTime(sameTime).build());

        assertHistory()
                .andExpect(jsonPath("$.content[0].sequenceNumber").value(2))
                .andExpect(jsonPath("$.content[1].sequenceNumber").value(3))
                .andExpect(jsonPath("$.content[2].sequenceNumber").value(4));
    }

    @Test
    @DisplayName("each entry carries both the normalized status and the carrier's own code")
    void exposesBothReadingsOfTheEvent() throws Exception {
        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_OFD", 5)
                .location("Ashgrove")
                .description("Out for delivery")
                .correlationId("corr-history")
                .build());

        assertHistory()
                .andExpect(jsonPath("$.content[0].normalizedEventType").value("OUT_FOR_DELIVERY"))
                .andExpect(jsonPath("$.content[0].carrierEventType").value("SP_OFD"))
                .andExpect(jsonPath("$.content[0].carrierCode").value("SWIFTPOST"))
                .andExpect(jsonPath("$.content[0].location").value("Ashgrove"))
                .andExpect(jsonPath("$.content[0].description").value("Out for delivery"))
                .andExpect(jsonPath("$.content[0].correlationId").value("corr-history"))
                .andExpect(jsonPath("$.content[0].processingStatus").value("APPLIED"))
                .andExpect(jsonPath("$.content[0].eventId").exists())
                .andExpect(jsonPath("$.content[0].eventTime").exists())
                .andExpect(jsonPath("$.content[0].receivedAt").exists())
                // The internal surrogate key is not part of the contract.
                .andExpect(jsonPath("$.content[0].id").doesNotExist());
    }

    @Test
    @DisplayName("superseded events appear in history alongside applied ones")
    void includesSupersededEvents() throws Exception {
        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_OFD", 5).build());
        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_TRANSIT", 3).build());

        assertHistory()
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].carrierEventType").value("SP_TRANSIT"))
                .andExpect(jsonPath("$.content[0].processingStatus").value("SUPERSEDED"))
                .andExpect(jsonPath("$.content[1].processingStatus").value("APPLIED"));
    }

    @Test
    @DisplayName("history is paginated with the shared envelope")
    void paginates() throws Exception {
        for (int i = 1; i <= 5; i++) {
            processor.process(CarrierEvents.swiftPost(shipmentId, "SP_TRANSIT", i).build());
        }

        mockMvc.perform(get("/api/shipments/{id}/events", shipmentId)
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.content[0].sequenceNumber").value(1));

        mockMvc.perform(get("/api/shipments/{id}/events", shipmentId)
                        .param("page", "2").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.content[0].sequenceNumber").value(5));
    }

    @Test
    @DisplayName("a shipment with no events yet returns an empty page, not a 404")
    void emptyHistoryIsAnEmptyPage() throws Exception {
        mockMvc.perform(get("/api/shipments/{id}/events", shipmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("an unknown shipment returns 404 as problem+json")
    void unknownShipmentIsNotFound() throws Exception {
        UUID unknown = UUID.randomUUID();

        mockMvc.perform(get("/api/shipments/{id}/events", unknown))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type")
                        .value("https://parcelflow.example/problems/shipment-not-found"))
                .andExpect(jsonPath("$.shipmentId").value(unknown.toString()));
    }

    @Test
    @DisplayName("a malformed shipment id returns 400")
    void malformedShipmentIdIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/shipments/{id}/events", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type")
                        .value("https://parcelflow.example/problems/malformed-request"));
    }

    @Test
    @DisplayName("a page size above the ceiling is rejected")
    void rejectsOversizedPageRequest() throws Exception {
        mockMvc.perform(get("/api/shipments/{id}/events", shipmentId).param("size", "5000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.size").exists());
    }

    private ResultActions assertHistory() throws Exception {
        return mockMvc.perform(get("/api/shipments/{id}/events", shipmentId))
                .andExpect(status().isOk());
    }
}
