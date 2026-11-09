package ca.vm.parcelflow.security;

import static ca.vm.parcelflow.support.security.TestIdentities.admin;
import static ca.vm.parcelflow.support.security.TestIdentities.retailer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.vm.parcelflow.shipment.ShipmentRepository;
import ca.vm.parcelflow.support.PostgresIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Retailer isolation through every shipment endpoint, with two retailers and an admin.
 *
 * <p>The property under test for reads of a specific shipment is stronger than "refused": another
 * retailer's shipment must be indistinguishable from one that does not exist — same status, same
 * problem type, same body apart from the identifier the caller sent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RetailerIsolationIntegrationTest extends PostgresIntegrationTest {

    private static final String ALPHA = "alpha-goods";
    private static final String BETA = "beta-supplies";
    private static final String PROBLEMS = "https://parcelflow.example/problems/";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShipmentRepository shipmentRepository;

    private String alphaShipmentId;
    private String alphaTrackingNumber;

    @BeforeEach
    void registerAlphaShipment() throws Exception {
        shipmentRepository.deleteAll();
        alphaTrackingNumber = "SP-ISO-" + UUID.randomUUID().toString().substring(0, 8);
        alphaShipmentId = id(register(retailer(ALPHA), ALPHA, alphaTrackingNumber)
                .andExpect(status().isCreated()).andReturn());
    }

    private org.springframework.test.web.servlet.ResultActions register(
            RequestPostProcessor who, String retailerId, String trackingNumber) throws Exception {
        return mockMvc.perform(post("/api/shipments").with(who).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"retailerId":"%s","customerId":"cust","trackingNumber":"%s","carrierCode":"SWIFTPOST"}"""
                        .formatted(retailerId, trackingNumber)));
    }

    private static String id(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.shipmentId");
    }

    /** The body with the one caller-supplied identifier removed, for "indistinguishable" comparisons. */
    private String notFoundBody(RequestPostProcessor who, String path, String identifier) throws Exception {
        String body = mockMvc.perform(get(path).with(who))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "shipment-not-found"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(ALPHA).doesNotContain("retailer");
        return body.replace(identifier, "<id>");
    }

    @Nested
    @DisplayName("reading a specific shipment")
    class SpecificShipment {

        @Test
        @DisplayName("by id: the owner and an admin see it; another retailer gets the nonexistent-shipment 404")
        void byId() throws Exception {
            String path = "/api/shipments/" + alphaShipmentId;
            mockMvc.perform(get(path).with(retailer(ALPHA))).andExpect(status().isOk());
            mockMvc.perform(get(path).with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.retailerId").value(ALPHA));

            String unknown = UUID.randomUUID().toString();
            assertThat(notFoundBody(retailer(BETA), path, alphaShipmentId))
                    .isEqualTo(notFoundBody(retailer(BETA), "/api/shipments/" + unknown, unknown));
        }

        // The same check against a warm Redis cache: RetailerIsolationCacheIntegrationTest. This class
        // runs with the cache disabled, so every read here goes to the database.

        @Test
        @DisplayName("by carrier and tracking number: another retailer gets the same 404 as a miss")
        void byTrackingNumber() throws Exception {
            String lookup = "/api/shipments/lookup?carrierCode=SWIFTPOST&trackingNumber=";
            mockMvc.perform(get(lookup + alphaTrackingNumber).with(retailer(ALPHA)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.shipmentId").value(alphaShipmentId));
            mockMvc.perform(get(lookup + alphaTrackingNumber).with(admin())).andExpect(status().isOk());

            String missing = "SP-ISO-MISSING";
            assertThat(notFoundBody(retailer(BETA), lookup + alphaTrackingNumber, alphaTrackingNumber))
                    .isEqualTo(notFoundBody(retailer(BETA), lookup + missing, missing));
        }

        @Test
        @DisplayName("tracking history: another retailer gets the nonexistent-shipment 404")
        void history() throws Exception {
            String path = "/api/shipments/" + alphaShipmentId + "/events";
            mockMvc.perform(get(path).with(retailer(ALPHA))).andExpect(status().isOk());
            mockMvc.perform(get(path).with(admin())).andExpect(status().isOk());

            String unknown = UUID.randomUUID().toString();
            assertThat(notFoundBody(retailer(BETA), path, alphaShipmentId))
                    .isEqualTo(notFoundBody(retailer(BETA), "/api/shipments/" + unknown + "/events", unknown));
        }

        @Test
        @DisplayName("notifications: another retailer gets the nonexistent-shipment 404")
        void notifications() throws Exception {
            String path = "/api/shipments/" + alphaShipmentId + "/notifications";
            mockMvc.perform(get(path).with(retailer(ALPHA))).andExpect(status().isOk());
            mockMvc.perform(get(path).with(admin())).andExpect(status().isOk());

            String unknown = UUID.randomUUID().toString();
            assertThat(notFoundBody(retailer(BETA), path, alphaShipmentId))
                    .isEqualTo(notFoundBody(retailer(BETA), "/api/shipments/" + unknown + "/notifications",
                            unknown));
        }
    }

    @Nested
    @DisplayName("retailer-scoped listing")
    class Listing {

        @Test
        @DisplayName("a retailer lists their own shipments; another retailer's list is 403")
        void listing() throws Exception {
            mockMvc.perform(get("/api/retailers/" + ALPHA + "/shipments").with(retailer(ALPHA)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));
            mockMvc.perform(get("/api/retailers/" + ALPHA + "/shipments").with(retailer(BETA)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value(PROBLEMS + "retailer-access-denied"));
            // Dashboard counts are this same endpoint with a status filter.
            mockMvc.perform(get("/api/retailers/" + ALPHA + "/shipments?status=LABEL_CREATED&size=1")
                            .with(retailer(BETA)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an admin lists any retailer")
        void adminListsAnyRetailer() throws Exception {
            mockMvc.perform(get("/api/retailers/" + ALPHA + "/shipments").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));
            mockMvc.perform(get("/api/retailers/" + BETA + "/shipments").with(admin()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("case and whitespace variants of a retailer id are other retailers: 403, never normalized")
        void retailerIdVariants() throws Exception {
            // Retailer ids compare exactly. A variant of another retailer's id is simply not the
            // caller's, and a variant of the caller's own id is not theirs either.
            for (String variant : new String[] {"ALPHA-GOODS", "alpha-goods%20", "%20alpha-goods", "Alpha-Goods"}) {
                for (RequestPostProcessor who : new RequestPostProcessor[] {retailer(BETA), retailer(ALPHA)}) {
                    mockMvc.perform(get(URI.create("/api/retailers/" + variant + "/shipments")).with(who))
                            .andExpect(status().isForbidden())
                            .andExpect(jsonPath("$.type").value(PROBLEMS + "retailer-access-denied"));
                }
            }
        }

        @Test
        @DisplayName("an encoded path separator in the retailer id is rejected before any controller runs")
        void encodedSeparatorIsRejected() throws Exception {
            // Spring Security's StrictHttpFirewall refuses %2F outright (400), so "../" tricks never
            // reach the retailer check. The body is not a problem document: nothing was dispatched.
            for (String variant : new String[] {"alpha-goods%2F..%2Fbeta-supplies", "beta-supplies%2Falpha-goods"}) {
                mockMvc.perform(get(URI.create("/api/retailers/" + variant + "/shipments")).with(retailer(BETA)))
                        .andExpect(status().isBadRequest());
            }
        }
    }

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        @DisplayName("a forged retailer id in the body is refused and nothing is stored")
        void forgedRetailerId() throws Exception {
            register(retailer(BETA), ALPHA, "SP-FORGED-1")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value(PROBLEMS + "retailer-access-denied"));
            assertThat(shipmentRepository.findByCarrierCodeAndTrackingNumber(
                    ca.vm.parcelflow.carrier.CarrierCode.SWIFTPOST, "SP-FORGED-1")).isEmpty();
        }

        @Test
        @DisplayName("an admin may register for any retailer")
        void adminRegistersForAnyRetailer() throws Exception {
            register(admin(), BETA, "SP-ADMIN-FOR-BETA").andExpect(status().isCreated())
                    .andExpect(jsonPath("$.retailerId").value(BETA));
        }

        @Test
        @DisplayName("a duplicate of another retailer's number is still 409 (global uniqueness), "
                + "but discloses neither the shipment nor its owner")
        void duplicateAcrossRetailers() throws Exception {
            String body = register(retailer(BETA), BETA, alphaTrackingNumber)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value(PROBLEMS + "duplicate-tracking-number"))
                    .andExpect(jsonPath("$.existingShipmentAccessible").value(false))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain(alphaShipmentId).doesNotContain(ALPHA).doesNotContain("shipmentId");
            assertThat(shipmentRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("a duplicate of the caller's own number says the existing shipment can be opened")
        void duplicateOfOwnShipment() throws Exception {
            String body = register(retailer(ALPHA), ALPHA, alphaTrackingNumber)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.existingShipmentAccessible").value(true))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(alphaShipmentId);
        }

        @Test
        @DisplayName("an admin colliding with any retailer's number may open the existing shipment")
        void adminDuplicate() throws Exception {
            register(admin(), BETA, alphaTrackingNumber)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.existingShipmentAccessible").value(true));
        }
    }

    @Nested
    @DisplayName("admin functionality")
    class AdminOnly {

        @Test
        @DisplayName("a retailer cannot list, read or retry failed events")
        void retailerCannotUseFailedEvents() throws Exception {
            UUID id = UUID.randomUUID();
            mockMvc.perform(get("/api/admin/failed-events").with(retailer(ALPHA)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value(PROBLEMS + "access-denied"));
            mockMvc.perform(get("/api/admin/failed-events/" + id).with(retailer(ALPHA)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/admin/failed-events/" + id + "/retry").with(retailer(ALPHA)).with(csrf()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value(PROBLEMS + "access-denied"));
        }

        @Test
        @DisplayName("an admin can, and the retry still needs a CSRF token")
        void adminRetryNeedsCsrf() throws Exception {
            UUID id = UUID.randomUUID();
            mockMvc.perform(get("/api/admin/failed-events").with(admin())).andExpect(status().isOk());
            mockMvc.perform(post("/api/admin/failed-events/" + id + "/retry").with(admin()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value(PROBLEMS + "csrf-token-invalid"));
            mockMvc.perform(post("/api/admin/failed-events/" + id + "/retry").with(admin()).with(csrf()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value(PROBLEMS + "failed-event-not-found"));
        }

        @Test
        @DisplayName("a retailer cannot reach the load-test publisher even when it is disabled")
        void retailerCannotReachInternal() throws Exception {
            mockMvc.perform(post("/internal/load/carrier-events").with(retailer(ALPHA)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
    }
}
