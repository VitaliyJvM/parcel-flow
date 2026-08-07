package ca.vm.parcelflow.infrastructure.observability;

import ca.vm.parcelflow.infrastructure.config.CacheConfiguration;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Counts Redis operations that failed and were swallowed.
 *
 * <p>Micrometer's Redis cache binder reports hits, misses, puts and removals — everything that
 * worked. It has nothing to say about the operations that threw, because from the cache's point of
 * view they never happened. Those are exactly the ones worth alerting on: the service is designed
 * to absorb them silently, which means without this counter a Redis outage shows up only as a
 * latency change and a stream of WARN lines nobody is watching.
 *
 * <p>An eviction failure is the one with a correctness cost — the entry stays stale until its TTL
 * expires — so {@code operation} is a tag rather than being lumped into one number.
 *
 * <p>Both tags are bounded: four operations, and one cache name.
 */
@Component
public class CacheFailureMetrics {

    private static final String NAME = "parcelflow.cache.failures";

    /** Every cache operation the error handler can be called for. */
    private static final List<String> OPERATIONS = List.of("get", "put", "evict", "clear");

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public CacheFailureMetrics(MeterRegistry registry) {
        this.registry = registry;

        // Registered up front for the one cache this service has, so the alert rule and the
        // dashboard panel have a series to read before the first failure. A rate() over a metric
        // that does not exist yet returns no data, which on a panel is indistinguishable from a
        // healthy zero — and the whole reason this counter exists is that these failures are
        // invisible by design.
        for (String operation : OPERATIONS) {
            counter(operation, CacheConfiguration.SHIPMENT_TRACKING_CACHE);
        }
    }

    public void failure(String operation, String cacheName) {
        counter(operation, cacheName).increment();
    }

    private Counter counter(String operation, String cacheName) {
        return counters.computeIfAbsent(operation + '/' + cacheName, ignored -> Counter.builder(NAME)
                .description("Cache operations that failed and were degraded to a PostgreSQL read "
                        + "or a stale entry")
                .tag("operation", operation)
                .tag("cache", cacheName)
                .register(registry));
    }
}
