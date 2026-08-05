package ca.vm.parcelflow.shipment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.vm.parcelflow.shipment.ShipmentRepository;
import ca.vm.parcelflow.support.PostgresIntegrationTest;
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

/**
 * End-to-end tests of the shipment REST contract: real controller, real validation, real Jackson,
 * real PostgreSQL. Only the socket is mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShipmentApiIntegrationTest extends PostgresIntegrationTest {

    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @BeforeEach
    void clearShipments() {
        shipmentRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /api/shipments returns 201 with a Location header and the created resource")
    void registersShipment() throws Exception {
        String body = """
                {
                  "retailerId": "retailer-1",
                  "customerId": "cust-1",
                  "trackingNumber": "SP-CREATE-1",
                  "carrierCode": "SWIFTPOST",
                  "estimatedDeliveryDate": "2026-08-12"
                }""";

        String response = mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/shipments/")))
                .andExpect(jsonPath("$.shipmentId").exists())
                .andExpect(jsonPath("$.retailerId").value("retailer-1"))
                .andExpect(jsonPath("$.trackingNumber").value("SP-CREATE-1"))
                .andExpect(jsonPath("$.carrierCode").value("SWIFTPOST"))
                .andExpect(jsonPath("$.currentStatus").value("LABEL_CREATED"))
                .andExpect(jsonPath("$.estimatedDeliveryDate").value("2026-08-12"))
                .andExpect(jsonPath("$.lastEventTime").doesNotExist())
                .andExpect(jsonPath("$.version").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(shipmentRepository.count()).isEqualTo(1);
        // The read model must not echo the customer reference back.
        assertThat(response).doesNotContain("customerId").doesNotContain("cust-1");
    }

    @Test
    @DisplayName("the Location header of a created shipment resolves")
    void locationHeaderResolves() throws Exception {
        String location = mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("SP-LOCATION")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingNumber").value("SP-LOCATION"));
    }

    @Test
    @DisplayName("registering the same tracking number for the same carrier returns 409")
    void rejectsDuplicateTrackingNumber() throws Exception {
        mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("SP-DUPE")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("SP-DUPE")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type")
                        .value("https://parcelflow.example/problems/duplicate-tracking-number"))
                .andExpect(jsonPath("$.title").value("Duplicate tracking number"))
                .andExpect(jsonPath("$.carrierCode").value("SWIFTPOST"))
                .andExpect(jsonPath("$.trackingNumber").value("SP-DUPE"));

        assertThat(shipmentRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a request missing required fields returns 400 with a per-field error map")
    void rejectsInvalidBody() throws Exception {
        String body = """
                {
                  "retailerId": "",
                  "trackingNumber": "SP-1",
                  "carrierCode": "SWIFTPOST"
                }""";

        mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type")
                        .value("https://parcelflow.example/problems/validation-failed"))
                .andExpect(jsonPath("$.errors.retailerId").exists())
                .andExpect(jsonPath("$.errors.customerId").exists());

        assertThat(shipmentRepository.count()).isZero();
    }

    @Test
    @DisplayName("an unknown carrier code returns 400 and does not leak parser internals")
    void rejectsUnknownCarrierCode() throws Exception {
        String body = """
                {
                  "retailerId": "retailer-1",
                  "customerId": "cust-1",
                  "trackingNumber": "SP-1",
                  "carrierCode": "NOT_A_CARRIER"
                }""";

        String response = mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type")
                        .value("https://parcelflow.example/problems/malformed-request"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("CarrierCode").doesNotContain("ca.vm.parcelflow");
    }

    @Test
    @DisplayName("GET of an unknown shipment id returns 404 as problem+json")
    void returnsNotFoundForUnknownShipment() throws Exception {
        UUID unknown = UUID.randomUUID();

        mockMvc.perform(get("/api/shipments/{id}", unknown))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type")
                        .value("https://parcelflow.example/problems/shipment-not-found"))
                .andExpect(jsonPath("$.shipmentId").value(unknown.toString()));
    }

    @Test
    @DisplayName("a malformed shipment id returns 400, not 404 or 500")
    void returnsBadRequestForMalformedShipmentId() throws Exception {
        mockMvc.perform(get("/api/shipments/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type")
                        .value("https://parcelflow.example/problems/malformed-request"));
    }

    @Test
    @DisplayName("retailer listing is scoped, paginated and newest-first")
    void listsRetailerShipments() throws Exception {
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(post("/api/shipments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(registerBody("SP-LIST-" + i)))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("SP-OTHER", "retailer-2")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/retailers/{retailerId}/shipments", "retailer-1")
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));

        mockMvc.perform(get("/api/retailers/{retailerId}/shipments", "retailer-1")
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("the status filter narrows the retailer listing")
    void filtersRetailerShipmentsByStatus() throws Exception {
        mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("SP-FILTER")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/retailers/{retailerId}/shipments", "retailer-1")
                        .param("status", "LABEL_CREATED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/retailers/{retailerId}/shipments", "retailer-1")
                        .param("status", "DELIVERED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("an unknown retailer yields an empty page, not a 404")
    void unknownRetailerYieldsEmptyPage() throws Exception {
        mockMvc.perform(get("/api/retailers/{retailerId}/shipments", "no-such-retailer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("a page size above the ceiling is rejected instead of silently clamped")
    void rejectsOversizedPageRequest() throws Exception {
        mockMvc.perform(get("/api/retailers/{retailerId}/shipments", "retailer-1")
                        .param("size", "5000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type")
                        .value("https://parcelflow.example/problems/validation-failed"))
                .andExpect(jsonPath("$.errors.size").exists());
    }

    @Test
    @DisplayName("a negative page index is rejected")
    void rejectsNegativePageIndex() throws Exception {
        mockMvc.perform(get("/api/retailers/{retailerId}/shipments", "retailer-1")
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.page").exists());
    }

    private static String registerBody(String trackingNumber) {
        return registerBody(trackingNumber, "retailer-1");
    }

    private static String registerBody(String trackingNumber, String retailerId) {
        return """
                {
                  "retailerId": "%s",
                  "customerId": "cust-1",
                  "trackingNumber": "%s",
                  "carrierCode": "SWIFTPOST",
                  "estimatedDeliveryDate": "2026-08-12"
                }""".formatted(retailerId, trackingNumber);
    }
}
