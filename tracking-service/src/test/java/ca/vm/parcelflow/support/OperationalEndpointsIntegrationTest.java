package ca.vm.parcelflow.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Guards the endpoints that operators and tooling depend on.
 *
 * <p>These are easy to break silently: a Boot upgrade renames a starter, springdoc stops matching
 * the Spring version, or a health indicator regresses to DOWN. Asserting the actual documented paths
 * and the health contributors catches all three at build time.
 *
 * <p>Runs against the full stack — PostgreSQL, Redpanda and Redis — because the readiness policy is
 * a claim about all three, and a claim like "Redis is excluded from readiness on purpose" can only
 * be demonstrated where Redis exists and readiness still does not depend on it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OperationalEndpointsIntegrationTest extends FullStackIntegrationTest {

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
    @DisplayName("readiness reflects the documented policy: PostgreSQL and Kafka in, Redis out")
    void readinessIncludesTheEssentialDependenciesOnly() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.kafka.status").value("UP"))
                // Redis is a cache. An outage costs read latency, never correctness, so it must
                // not be able to take a working instance out of rotation.
                .andExpect(jsonPath("$.components.redis").doesNotExist());

        // It is still visible in the aggregate health endpoint, so the outage is not invisible.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(jsonPath("$.components.redis.status").value("UP"));
    }

    @Test
    @DisplayName("liveness depends on no external dependency")
    void livenessIsIndependentOfDependencies() throws Exception {
        // Restarting the process does not fix a database that is down. Anything that a restart
        // cannot repair must stay out of liveness, or an infrastructure blip becomes a restart
        // storm across every instance at once. The only contributor is the application's own
        // liveness state.
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(jsonPath("$.components.livenessState.status").value("UP"))
                .andExpect(jsonPath("$.components.db").doesNotExist())
                .andExpect(jsonPath("$.components.kafka").doesNotExist())
                .andExpect(jsonPath("$.components.redis").doesNotExist());
    }

    @Test
    @DisplayName("the Kafka health contributor reports the cluster it reached")
    void kafkaHealthNamesTheCluster() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(jsonPath("$.components.kafka.status").value("UP"))
                .andExpect(jsonPath("$.components.kafka.details.nodes").value(1));
    }

    @Test
    @DisplayName("sensitive actuator endpoints are not exposed")
    void sensitiveEndpointsAreNotExposed() throws Exception {
        // Each of these either leaks configuration — including the datasource password, in the
        // case of env and configprops — or lets an unauthenticated caller change the running
        // service. The exposure list in application.yml is an allowlist for exactly this reason.
        for (String endpoint : new String[] {"env", "configprops", "beans", "loggers", "threaddump",
                "heapdump", "mappings", "shutdown"}) {
            mockMvc.perform(get("/actuator/" + endpoint))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("the Prometheus scrape endpoint serves metrics")
    void prometheusEndpointServesMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a caller's correlation id is propagated and echoed back")
    void propagatesACallerSuppliedCorrelationId() throws Exception {
        mockMvc.perform(get("/api/shipments/{id}", "00000000-0000-0000-0000-000000000000")
                        .header("X-Correlation-Id", "caller-supplied-id"))
                .andExpect(status().isNotFound())
                // Present on an error response too: the request someone needs to trace is usually
                // the one that failed.
                .andExpect(header().string("X-Correlation-Id", "caller-supplied-id"));
    }

    @Test
    @DisplayName("a request without a correlation id is given one")
    void generatesACorrelationIdWhenTheCallerSendsNone() throws Exception {
        String generated = mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getHeader("X-Correlation-Id");

        assertThat(generated).isNotBlank();
    }

    @Test
    @DisplayName("the load-testing publish endpoint is not exposed by default")
    void loadTestingEndpointIsDisabledByDefault() throws Exception {
        mockMvc.perform(post("/internal/load/carrier-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
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
