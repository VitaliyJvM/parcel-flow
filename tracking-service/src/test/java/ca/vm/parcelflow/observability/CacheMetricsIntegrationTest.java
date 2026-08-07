package ca.vm.parcelflow.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.infrastructure.config.CacheConfiguration;
import ca.vm.parcelflow.shipment.ShipmentRepository;
import ca.vm.parcelflow.shipment.ShipmentService;
import ca.vm.parcelflow.shipment.domain.Shipment;
import ca.vm.parcelflow.support.CarrierEvents;
import ca.vm.parcelflow.support.RedisIntegrationTest;
import ca.vm.parcelflow.tracking.TrackingEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * Cache hit and miss metrics against a real Redis.
 *
 * <p>These are Micrometer's built-in cache meters rather than counters this project declares, which
 * is the point of the test: they only report anything if {@code RedisCacheManager} was built with
 * statistics enabled. Miss that one builder call and the meters still exist, still get scraped, and
 * read zero forever — a dashboard showing a cache with no traffic, which looks like a quiet system
 * rather than a broken instrument.
 */
@SpringBootTest
@ActiveProfiles("test")
class CacheMetricsIntegrationTest extends RedisIntegrationTest {

    @Autowired
    private ShipmentService shipmentService;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private TrackingEventRepository trackingEventRepository;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private MeterRegistry registry;

    private UUID shipmentId;

    @BeforeEach
    void registerShipment() {
        trackingEventRepository.deleteAll();
        shipmentRepository.deleteAll();

        Cache cache = cacheManager.getCache(CacheConfiguration.SHIPMENT_TRACKING_CACHE);
        assertThat(cache).isNotNull();
        cache.clear();

        shipmentId = shipmentRepository.saveAndFlush(Shipment.register(
                        UUID.randomUUID(), "retailer-1", "cust-1", "SP-CACHE-METRICS-1",
                        CarrierCode.SWIFTPOST, LocalDate.parse("2026-08-12"), CarrierEvents.T0))
                .getShipmentId();
    }

    @Test
    @DisplayName("a miss then a hit are both counted, so the hit ratio is computable")
    void countsMissesAndHits() {
        double missesBefore = cacheGets("miss");
        double hitsBefore = cacheGets("hit");
        double putsBefore = cachePuts();

        // First read: nothing cached yet.
        shipmentService.getShipmentTracking(shipmentId);

        // Direction, not an exact count. One @Cacheable invocation does not map to exactly one
        // Redis GET — the cache abstraction looks the key up more than once on the miss path — and
        // an assertion pinned to "+1" would be asserting a Spring implementation detail rather
        // than the property that matters, which is that a miss is recorded as a miss.
        assertThat(cacheGets("miss")).isGreaterThan(missesBefore);
        assertThat(cachePuts()).isGreaterThan(putsBefore);
        assertThat(cacheGets("hit")).isEqualTo(hitsBefore);

        double missesAfterFirstRead = cacheGets("miss");

        // Second read: served from Redis.
        shipmentService.getShipmentTracking(shipmentId);

        assertThat(cacheGets("hit")).isGreaterThan(hitsBefore);
        assertThat(cacheGets("miss")).isEqualTo(missesAfterFirstRead);
    }

    @Test
    @DisplayName("the documented cache meters are all bound to the shipment-tracking cache")
    void bindsEveryDocumentedCacheMeter() {
        // The set Spring Data Redis actually reports. There is deliberately no assertion for
        // cache.evictions: Redis exposes no eviction statistic, so that meter does not exist here,
        // and docs/operations.md says so rather than shipping a dashboard panel that is empty by
        // construction. Redis evictions are a server-level concern, visible in INFO stats.
        for (String meter : new String[] {"cache.gets", "cache.puts", "cache.removals"}) {
            assertThat(registry.find(meter)
                    .tag("cache", CacheConfiguration.SHIPMENT_TRACKING_CACHE)
                    .functionCounters())
                    .as("%s is bound to the shipment-tracking cache", meter)
                    .isNotEmpty();
        }
    }

    /**
     * A {@code FunctionCounter}, not a {@code Counter}: the binder reads Spring Data Redis's own
     * statistics object rather than counting increments itself, so asking the registry for a
     * counter finds nothing and the test fails with a confusing "no meter matches".
     */
    private double cacheGets(String result) {
        return registry.get("cache.gets")
                .tags("cache", CacheConfiguration.SHIPMENT_TRACKING_CACHE, "result", result)
                .functionCounter()
                .count();
    }

    private double cachePuts() {
        return registry.get("cache.puts")
                .tag("cache", CacheConfiguration.SHIPMENT_TRACKING_CACHE)
                .functionCounter()
                .count();
    }
}
