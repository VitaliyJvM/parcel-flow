package ca.vm.parcelflow.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Guards the endpoints that operators and tooling depend on.
 *
 * <p>These are easy to break silently: a Boot upgrade renames a starter, springdoc stops matching
 * the Spring version, or a health indicator regresses to DOWN. Asserting the actual documented paths
 * and the DB health contributor catches all three at build time.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OperationalEndpointsIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("health reports UP and includes the database contributor")
    void healthIsUpWithDatabaseDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    @DisplayName("liveness and readiness probes are exposed")
    void probesAreExposed() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("the Prometheus scrape endpoint serves metrics")
    void prometheusEndpointServesMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the OpenAPI document is generated and describes every Stage 1 endpoint")
    void openApiDocumentDescribesAllEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").value("ParcelFlow Tracking Service API"))
                .andExpect(jsonPath("$.paths./api/shipments.post").exists())
                .andExpect(jsonPath("$.paths./api/shipments/{shipmentId}.get").exists())
                .andExpect(jsonPath(
                        "$.paths./api/retailers/{retailerId}/shipments.get").exists());
    }
}
