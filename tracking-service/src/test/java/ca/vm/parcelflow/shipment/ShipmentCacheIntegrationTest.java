package ca.vm.parcelflow.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.infrastructure.config.CacheConfiguration;
import ca.vm.parcelflow.shipment.api.ShipmentResponse;
import ca.vm.parcelflow.shipment.domain.Shipment;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import ca.vm.parcelflow.support.CarrierEvents;
import ca.vm.parcelflow.support.RedisIntegrationTest;
import ca.vm.parcelflow.tracking.TrackingEventProcessor;
import ca.vm.parcelflow.tracking.TrackingEventRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Cache behaviour against a real Redis: hits, misses, and invalidation after an event.
 *
 * <p>Asserts through the {@code CacheManager} and a raw {@code StringRedisTemplate} rather than by
 * timing the endpoint. A latency-based assertion for "this was a cache hit" is a coin flip on a
 * loaded CI machine; looking at whether the key exists is not.
 */
@SpringBootTest
@ActiveProfiles("test")
class ShipmentCacheIntegrationTest extends RedisIntegrationTest {

    @Autowired
    private ShipmentService shipmentService;

    @Autowired
    private TrackingEventProcessor processor;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private TrackingEventRepository trackingEventRepository;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final Duration CACHE_SETTLE = Duration.ofSeconds(5);

    private UUID shipmentId;

    @BeforeEach
    void registerShipment() {
        trackingEventRepository.deleteAll();
        shipmentRepository.deleteAll();

        // Clear through the cache abstraction rather than grabbing a raw connection and calling
        // FLUSHALL. Borrowing a connection from the factory means owning its lifecycle, and a
        // connection left unclosed here made later cache writes fail — silently, because the error
        // handler swallows them, which showed up as an entry that mysteriously failed to appear.
        Cache cache = cacheManager.getCache(CacheConfiguration.SHIPMENT_TRACKING_CACHE);
        assertThat(cache).isNotNull();
        cache.clear();

        shipmentId = shipmentRepository.saveAndFlush(Shipment.register(
                        UUID.randomUUID(), "retailer-1", "cust-1", "SP-CACHE-1",
                        CarrierCode.SWIFTPOST, LocalDate.parse("2026-08-12"), CarrierEvents.T0))
                .getShipmentId();
    }

    @Test
    @DisplayName("a cache miss populates Redis under a readable key")
    void missPopulatesTheCache() {
        assertThat(cachedEntry()).isNull();

        ShipmentResponse response = shipmentService.getShipmentTracking(shipmentId);

        assertThat(response.currentStatus()).isEqualTo(ShipmentStatus.LABEL_CREATED);
        awaitCached();
        // A prefixed, human-readable key: an operator debugging a stale response has to be able to
        // find the entry in redis-cli.
        assertThat(redisTemplate.hasKey("parcelflow:shipment-tracking:" + shipmentId)).isTrue();
    }

    @Test
    @DisplayName("a second read is served from the cache, not the database")
    void secondReadIsAHit() {
        shipmentService.getShipmentTracking(shipmentId);

        // Change the row behind the cache's back, without going through the eviction path. A read
        // that still returns the old value proves it came from Redis.
        shipmentRepository.findById(shipmentId).ifPresent(shipment -> {
            shipment.recordEvent(ShipmentStatus.DELIVERED, CarrierEvents.T0.plusSeconds(60), 9,
                    CarrierEvents.T0.plusSeconds(61));
            shipmentRepository.saveAndFlush(shipment);
        });

        assertThat(shipmentService.getShipmentTracking(shipmentId).currentStatus())
                .isEqualTo(ShipmentStatus.LABEL_CREATED);
    }

    @Test
    @DisplayName("an applied event evicts the entry, and the next read sees the new status")
    void appliedEventInvalidatesTheCache() {
        shipmentService.getShipmentTracking(shipmentId);
        awaitCached();

        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_OFD", 5).build());

        // Eviction runs on AFTER_COMMIT, so it is ordered after the transaction rather than
        // instantaneous. Bounded polling, not a sleep.
        awaitNotCached();

        assertThat(shipmentService.getShipmentTracking(shipmentId).currentStatus())
                .isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    @DisplayName("a superseded event leaves the cache alone: the shipment did not change")
    void supersededEventDoesNotInvalidate() {
        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_OFD", 50).build());
        shipmentService.getShipmentTracking(shipmentId);
        awaitCached();

        // Arrives too late to change anything. Evicting here would throw away a still-correct entry
        // and force a needless database read.
        processor.process(CarrierEvents.swiftPost(shipmentId, "SP_TRANSIT", 40).build());

        // Still cached. Given a moment in which an eviction could have happened, none did.
        assertThat(cachedEntry()).isNotNull();
        assertThat(shipmentService.getShipmentTracking(shipmentId).currentStatus())
                .isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    @DisplayName("a 404 is not cached, so a shipment created a second later is visible immediately")
    void notFoundIsNotCached() {
        UUID unknown = UUID.randomUUID();

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> shipmentService.getShipmentTracking(unknown))
                .isInstanceOf(ShipmentNotFoundException.class);

        assertThat(redisTemplate.hasKey("parcelflow:shipment-tracking:" + unknown)).isFalse();

        // Now register it under that id and read again: no stale negative entry stands in the way.
        shipmentRepository.saveAndFlush(Shipment.register(
                unknown, "retailer-1", "cust-1", "SP-LATE-1", CarrierCode.SWIFTPOST,
                LocalDate.parse("2026-08-12"), CarrierEvents.T0));

        assertThat(shipmentService.getShipmentTracking(unknown).shipmentId()).isEqualTo(unknown);
    }

    @Test
    @DisplayName("the cached value round-trips every field, including Instant and LocalDate")
    void cachedValueRoundTripsFaithfully() {
        ShipmentResponse fresh = shipmentService.getShipmentTracking(shipmentId);
        ShipmentResponse cached = shipmentService.getShipmentTracking(shipmentId);

        // Redis serialization must not quietly lose precision or reformat a date; a cached response
        // that differs from an uncached one is worse than no cache at all.
        assertThat(cached).isEqualTo(fresh);
        assertThat(cached.createdAt()).isEqualTo(fresh.createdAt());
        assertThat(cached.estimatedDeliveryDate()).isEqualTo(LocalDate.parse("2026-08-12"));
    }

    /**
     * Waits for the entry to appear.
     *
     * <p>A bounded wait rather than a bare assertion because the cache is eventually consistent
     * with the read that populated it: the write is a separate round trip to Redis, and the
     * project's rule for anything asynchronous is to poll with a deadline rather than to assume
     * an ordering the framework does not promise.
     */
    private void awaitCached() {
        Awaitility.await().atMost(CACHE_SETTLE)
                .pollInterval(Duration.ofMillis(20))
                .untilAsserted(() -> assertThat(cachedEntry()).isNotNull());
    }

    private void awaitNotCached() {
        Awaitility.await().atMost(CACHE_SETTLE)
                .pollInterval(Duration.ofMillis(20))
                .untilAsserted(() -> assertThat(cachedEntry()).isNull());
    }

    private Cache.ValueWrapper cachedEntry() {
        Cache cache = cacheManager.getCache(CacheConfiguration.SHIPMENT_TRACKING_CACHE);
        assertThat(cache).isNotNull();
        return cache.get(shipmentId);
    }
}
