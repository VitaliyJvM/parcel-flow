package ca.vm.parcelflow.security;

import static ca.vm.parcelflow.support.security.TestIdentities.admin;
import static ca.vm.parcelflow.support.security.TestIdentities.retailer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.vm.parcelflow.infrastructure.config.CacheConfiguration;
import ca.vm.parcelflow.shipment.ShipmentRepository;
import ca.vm.parcelflow.support.RedisIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Retailer isolation on the cached read path, against a real Redis.
 *
 * <p>{@code GET /api/shipments/{id}} is served from the shipment tracking cache, and the cached
 * value is shared by every caller: it is keyed by shipment id, not by user. The retailer check
 * therefore has to run on the value the cache returns. This class proves that with the cache
 * actually populated — asserted through the {@code CacheManager}, not inferred from timing — for
 * both orders: the owner warming the cache before another retailer reads, and another retailer's
 * refused read warming it before the owner does.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RetailerIsolationCacheIntegrationTest extends RedisIntegrationTest {

    private static final String ALPHA = "alpha-goods";
    private static final String BETA = "beta-supplies";
    private static final String PROBLEMS = "https://parcelflow.example/problems/";
    private static final Duration CACHE_SETTLE = Duration.ofSeconds(5);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private CacheManager cacheManager;

    private String shipmentId;

    @BeforeEach
    void registerAlphaShipment() throws Exception {
        shipmentRepository.deleteAll();
        cache().clear();
        String body = mockMvc.perform(post("/api/shipments").with(retailer(ALPHA)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"retailerId":"%s","customerId":"cust","trackingNumber":"SP-ISO-CACHE-%s",\
                                "carrierCode":"SWIFTPOST"}"""
                                .formatted(ALPHA, UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        shipmentId = JsonPath.read(body, "$.shipmentId");
        assertThat(cachedEntry()).as("registration does not populate the cache").isNull();
    }

    @Test
    @DisplayName("owner warms the cache; another retailer's read hits it and still gets the nonexistent-shipment 404")
    void ownerWarmsTheCache() throws Exception {
        read(retailer(ALPHA)).andExpect(status().isOk()).andExpect(jsonPath("$.retailerId").value(ALPHA));
        awaitCached();

        String refused = notFound(retailer(BETA), shipmentId);
        String unknownId = UUID.randomUUID().toString();
        assertThat(refused).isEqualTo(notFound(retailer(BETA), unknownId));
        // Still cached: the refusal neither evicted nor bypassed the entry.
        assertThat(cachedEntry()).isNotNull();

        read(admin()).andExpect(status().isOk()).andExpect(jsonPath("$.retailerId").value(ALPHA));
    }

    @Test
    @DisplayName("another retailer's refused read warms the cache; the owner is still served, the refusal repeats")
    void refusedReadWarmsTheCache() throws Exception {
        notFound(retailer(BETA), shipmentId);
        // The unauthorized read populated the shared entry (harmless: nothing was returned).
        awaitCached();

        read(retailer(ALPHA)).andExpect(status().isOk()).andExpect(jsonPath("$.shipmentId").value(shipmentId));
        notFound(retailer(BETA), shipmentId);
    }

    private ResultActions read(RequestPostProcessor who) throws Exception {
        return mockMvc.perform(get("/api/shipments/" + shipmentId).with(who));
    }

    /** The 404 body with the requested id replaced, so two refusals can be compared byte for byte. */
    private String notFound(RequestPostProcessor who, String id) throws Exception {
        String body = mockMvc.perform(get("/api/shipments/" + id).with(who))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "shipment-not-found"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(ALPHA).doesNotContain("retailer");
        return body.replace(id, "<id>");
    }

    private Cache cache() {
        Cache cache = cacheManager.getCache(CacheConfiguration.SHIPMENT_TRACKING_CACHE);
        assertThat(cache).isNotNull();
        return cache;
    }

    private Cache.ValueWrapper cachedEntry() {
        return cache().get(UUID.fromString(shipmentId));
    }

    private void awaitCached() {
        Awaitility.await().atMost(CACHE_SETTLE)
                .pollInterval(Duration.ofMillis(20))
                .untilAsserted(() -> assertThat(cachedEntry()).as("tracking cache entry").isNotNull());
    }
}
