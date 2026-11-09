package ca.vm.parcelflow.shipment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.vm.parcelflow.shipment.ShipmentRepository;
import ca.vm.parcelflow.support.RedisIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The {@code GET /api/shipments/lookup} contract against a real PostgreSQL: matching semantics,
 * parameter validation, problem documents and correlation-id propagation.
 *
 * <p>Same base class and profile as {@link ShipmentApiIntegrationTest}, so the two share one
 * cached Spring context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShipmentLookupApiIntegrationTest extends RedisIntegrationTest {

    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;
    private static final String PROBLEMS = "https://parcelflow.example/problems/";
    private static final String LOOKUP = "/api/shipments/lookup";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @BeforeEach
    void clearShipments() {
        shipmentRepository.deleteAll();
    }

    // --- matching ---------------------------------------------------------------------------

    @Test
    @DisplayName("finds a registered shipment and returns the same read model as GET by id")
    void findsRegisteredShipment() throws Exception {
        String shipmentId = register("SWIFTPOST", "SP-LOOKUP-1");

        String response = mockMvc.perform(lookup("SWIFTPOST", "SP-LOOKUP-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.shipmentId").value(shipmentId))
                .andExpect(jsonPath("$.retailerId").value("retailer-1"))
                .andExpect(jsonPath("$.trackingNumber").value("SP-LOOKUP-1"))
                .andExpect(jsonPath("$.carrierCode").value("SWIFTPOST"))
                .andExpect(jsonPath("$.currentStatus").value("LABEL_CREATED"))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Same projection as the by-id read: no customer reference.
        assertThat(response).doesNotContain("customerId").doesNotContain("cust-1");
    }

    @Test
    @DisplayName("an unknown tracking number returns 404 shipment-not-found with the inputs echoed")
    void returnsNotFoundForUnknownTrackingNumber() throws Exception {
        mockMvc.perform(lookup("SWIFTPOST", "SP-NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEMS + "shipment-not-found"))
                .andExpect(jsonPath("$.title").value("Shipment not found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.carrierCode").value("SWIFTPOST"))
                .andExpect(jsonPath("$.trackingNumber").value("SP-NOPE"))
                .andExpect(jsonPath("$.shipmentId").doesNotExist());
    }

    @Test
    @DisplayName("the same tracking number under two carriers resolves to two different shipments")
    void distinguishesCarriersWithIdenticalTrackingNumbers() throws Exception {
        String swiftpost = register("SWIFTPOST", "SHARED-123");
        String pacifica = register("PACIFICA", "SHARED-123");
        assertThat(swiftpost).isNotEqualTo(pacifica);

        mockMvc.perform(lookup("SWIFTPOST", "SHARED-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipmentId").value(swiftpost))
                .andExpect(jsonPath("$.carrierCode").value("SWIFTPOST"));
        mockMvc.perform(lookup("PACIFICA", "SHARED-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipmentId").value(pacifica))
                .andExpect(jsonPath("$.carrierCode").value("PACIFICA"));
    }

    @Test
    @DisplayName("a tracking number registered for one carrier is not found under another")
    void doesNotMatchAcrossCarriers() throws Exception {
        register("SWIFTPOST", "SP-ONLY");

        mockMvc.perform(lookup("NORDEX", "SP-ONLY"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.carrierCode").value("NORDEX"));
    }

    @Test
    @DisplayName("matching is case-sensitive, exactly like the duplicate-registration constraint")
    void matchesCaseSensitively() throws Exception {
        String upper = register("SWIFTPOST", "SP-CASE-A");
        // The unique constraint compares verbatim, so a different-case number is a new shipment.
        String lower = register("SWIFTPOST", "sp-case-a");
        assertThat(upper).isNotEqualTo(lower);

        mockMvc.perform(lookup("SWIFTPOST", "SP-CASE-A"))
                .andExpect(jsonPath("$.shipmentId").value(upper));
        mockMvc.perform(lookup("SWIFTPOST", "sp-case-a"))
                .andExpect(jsonPath("$.shipmentId").value(lower));
        mockMvc.perform(lookup("SWIFTPOST", "Sp-Case-A"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the tracking number is not trimmed: surrounding whitespace is part of the value")
    void doesNotTrimTrackingNumber() throws Exception {
        register("SWIFTPOST", "SP-TRIM");

        mockMvc.perform(lookup("SWIFTPOST", " SP-TRIM "))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.trackingNumber").value(" SP-TRIM "));
    }

    @Test
    @DisplayName("reserved URL characters in a tracking number round-trip through the query string")
    void handlesReservedCharacters() throws Exception {
        String shipmentId = register("PACIFICA", "PX/12%3?#&=+");

        mockMvc.perform(lookup("PACIFICA", "PX/12%3?#&=+"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipmentId").value(shipmentId));
    }

    // --- routing ----------------------------------------------------------------------------

    @Test
    @DisplayName("/lookup is not captured by /{shipmentId}, and lookup by id keeps working")
    void literalLookupPathWinsOverShipmentIdPattern() throws Exception {
        String shipmentId = register("SWIFTPOST", "SP-ROUTE");

        // Were /lookup routed to /{shipmentId}, this would be a UUID type mismatch.
        mockMvc.perform(get(LOOKUP))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "validation-failed"));
        mockMvc.perform(get("/api/shipments/{id}", shipmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingNumber").value("SP-ROUTE"));
    }

    // --- validation -------------------------------------------------------------------------

    @Test
    @DisplayName("a missing carrierCode is a 400 validation failure, not a 500")
    void rejectsMissingCarrierCode() throws Exception {
        mockMvc.perform(get(LOOKUP).param("trackingNumber", "SP-1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEMS + "validation-failed"))
                .andExpect(jsonPath("$.errors.carrierCode").value("is required"));
    }

    @Test
    @DisplayName("an empty carrierCode is reported as missing")
    void rejectsEmptyCarrierCode() throws Exception {
        mockMvc.perform(lookup("", "SP-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "validation-failed"))
                .andExpect(jsonPath("$.errors.carrierCode").exists());
    }

    @Test
    @DisplayName("a missing trackingNumber is a 400 validation failure")
    void rejectsMissingTrackingNumber() throws Exception {
        mockMvc.perform(get(LOOKUP).param("carrierCode", "SWIFTPOST"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "validation-failed"))
                .andExpect(jsonPath("$.errors.trackingNumber").value("is required"));
    }

    @Test
    @DisplayName("a blank trackingNumber fails @NotBlank")
    void rejectsBlankTrackingNumber() throws Exception {
        mockMvc.perform(lookup("SWIFTPOST", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEMS + "validation-failed"))
                .andExpect(jsonPath("$.errors.trackingNumber").exists());
    }

    @Test
    @DisplayName("a trackingNumber longer than the registration limit fails @Size")
    void rejectsOverlongTrackingNumber() throws Exception {
        mockMvc.perform(lookup("SWIFTPOST", "X".repeat(129)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "validation-failed"))
                .andExpect(jsonPath("$.errors.trackingNumber").exists());

        // The limit itself is accepted (and simply not found).
        mockMvc.perform(lookup("SWIFTPOST", "X".repeat(128)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an unknown carrier code is a 400 that names the parameter and leaks no internals")
    void rejectsUnknownCarrierCode() throws Exception {
        String response = mockMvc.perform(lookup("NOT_A_CARRIER", "SP-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "malformed-request"))
                .andExpect(jsonPath("$.errors.carrierCode").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("ca.vm.parcelflow").doesNotContain("Exception");
    }

    @Test
    @DisplayName("carrier codes are case-sensitive, as they are in the registration body")
    void rejectsLowerCaseCarrierCode() throws Exception {
        mockMvc.perform(lookup("swiftpost", "SP-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "malformed-request"));
    }

    // --- correlation ------------------------------------------------------------------------

    @Test
    @DisplayName("a caller-supplied X-Correlation-Id is echoed on success and on 404")
    void propagatesCorrelationId() throws Exception {
        register("SWIFTPOST", "SP-CORR");

        mockMvc.perform(lookup("SWIFTPOST", "SP-CORR").header("X-Correlation-Id", "corr-lookup-ok"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "corr-lookup-ok"));
        mockMvc.perform(lookup("SWIFTPOST", "SP-MISSING").header("X-Correlation-Id", "corr-lookup-404"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-Id", "corr-lookup-404"));
        mockMvc.perform(get(LOOKUP).header("X-Correlation-Id", "corr-lookup-400"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Correlation-Id", "corr-lookup-400"));
    }

    // --- helpers ----------------------------------------------------------------------------

    private static MockHttpServletRequestBuilder lookup(String carrierCode, String trackingNumber) {
        return get(LOOKUP).param("carrierCode", carrierCode).param("trackingNumber", trackingNumber);
    }

    private String register(String carrierCode, String trackingNumber) throws Exception {
        String body = """
                {
                  "retailerId": "retailer-1",
                  "customerId": "cust-1",
                  "trackingNumber": %s,
                  "carrierCode": "%s"
                }""".formatted(jsonString(trackingNumber), carrierCode);
        String response = mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.shipmentId");
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
