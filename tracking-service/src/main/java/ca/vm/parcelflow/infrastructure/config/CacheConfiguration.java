package ca.vm.parcelflow.infrastructure.config;

import ca.vm.parcelflow.infrastructure.observability.CacheFailureMetrics;
import ca.vm.parcelflow.shipment.api.ShipmentResponse;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis caching for the shipment tracking response.
 *
 * <p>Redis is a read accelerator and nothing else. PostgreSQL is the source of truth; every value
 * in Redis is derived, has a TTL, and can be thrown away at any moment without affecting
 * correctness.
 *
 * <p>The whole cache is switchable via {@code parcelflow.cache.enabled}. That is not a convenience
 * for tests — it is the operational lever that follows from the design claim. If the service is
 * genuinely correct without Redis, then turning Redis off must be a supported configuration, and
 * the tests that run with it off are the proof.
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(EventProcessingProperties.class)
public class CacheConfiguration implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfiguration.class);

    /** The only cache. A constant so the read and eviction sides cannot misspell it apart. */
    public static final String SHIPMENT_TRACKING_CACHE = "shipment-tracking";

    /**
     * Resolved lazily, through a provider.
     *
     * <p>{@link CachingConfigurer} is consumed while the cache interceptor is being built, which is
     * early — early enough that asking for a fully-initialized {@code MeterRegistry} at that point
     * risks forcing metrics infrastructure into existence before its own configuration has been
     * applied. The provider defers the lookup to the first cache failure, by which time the context
     * is running. If metrics are somehow unavailable, a missing counter must not turn a swallowed
     * Redis error into a thrown one.
     */
    private final ObjectProvider<CacheFailureMetrics> cacheFailureMetrics;

    public CacheConfiguration(ObjectProvider<CacheFailureMetrics> cacheFailureMetrics) {
        this.cacheFailureMetrics = cacheFailureMetrics;
    }

    private void countFailure(String operation, Cache cache) {
        cacheFailureMetrics.ifAvailable(metrics -> metrics.failure(operation, cache.getName()));
    }

    /**
     * Makes every cache operation best-effort.
     *
     * <p>The default handler rethrows, which would turn "Redis is down" into "the tracking API is
     * down" — and worse, would let a failed eviction throw out of a transaction that had already
     * committed a tracking event. Swallowing and logging means an unavailable Redis degrades the
     * service to the speed of PostgreSQL and nothing else.
     *
     * <p>Wired in by implementing {@link CachingConfigurer}. A bare {@code @Bean CacheErrorHandler}
     * is <em>not</em> picked up by the cache interceptor — it sits in the context doing nothing
     * while every Redis failure propagates to the caller, which is the opposite of the intent.
     *
     * <p>A failed <em>eviction</em> is the one case with a correctness cost: the entry stays stale
     * until its TTL expires. That is why the TTL exists and why it is short, and why this logs at
     * warn — the window should be visible rather than silent.
     */
    @Override
    @Bean
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {

            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                countFailure("get", cache);
                log.warn("Cache read failed for {}::{}; falling through to PostgreSQL: {}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCachePutError(
                    RuntimeException exception, Cache cache, Object key, Object value) {
                countFailure("put", cache);
                log.warn("Cache write failed for {}::{}; the response is still correct: {}",
                        cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                countFailure("evict", cache);
                log.warn("Cache eviction failed for {}::{}; the entry may serve stale data until "
                        + "its TTL expires: {}", cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                countFailure("clear", cache);
                log.warn("Cache clear failed for {}: {}", cache.getName(), exception.getMessage());
            }
        };
    }

    @Configuration
    @ConditionalOnProperty(name = "parcelflow.cache.enabled", havingValue = "true", matchIfMissing = true)
    static class RedisCacheManagerConfiguration {

        @Bean
        public CacheManager cacheManager(
                RedisConnectionFactory connectionFactory, EventProcessingProperties properties) {

            RedisCacheConfiguration configuration = RedisCacheConfiguration.defaultCacheConfig()
                    // A TTL even though every write path evicts explicitly. The TTL is the backstop
                    // for what eviction cannot cover: a missed event, a bug in the invalidation
                    // path, or a value written by a process that then died. Without it, one stale
                    // entry is stale forever.
                    .entryTtl(properties.shipmentCacheTtl())

                    // Null results are not cached, so a 404 cannot be pinned in Redis until its TTL
                    // expires. A shipment that does not exist yet very often exists a second later.
                    .disableCachingNullValues()

                    .computePrefixWith(cacheName -> "parcelflow:" + cacheName + ":")
                    .serializeKeysWith(RedisSerializationContext.SerializationPair
                            .fromSerializer(new StringRedisSerializer()))

                    // A serializer pinned to one type, not a generic one that embeds a class name
                    // in the payload. Smaller entries, and nothing in Redis can talk this
                    // application into instantiating a class of its choosing.
                    .serializeValuesWith(RedisSerializationContext.SerializationPair
                            .fromSerializer(new JacksonJsonRedisSerializer<>(ShipmentResponse.class)));

            return RedisCacheManager.builder(connectionFactory)
                    .cacheDefaults(configuration)

                    // Declared up front rather than created on first use so that Boot's cache
                    // meter binder has a cache to bind at startup. A cache that materializes on
                    // the first request is invisible to metrics until then, which is exactly when
                    // someone is looking at the dashboard.
                    .initialCacheNames(Set.of(SHIPMENT_TRACKING_CACHE))

                    // Without this, Spring Data Redis keeps no statistics and every
                    // cache.gets/cache.puts series Micrometer publishes reads zero forever — a
                    // dashboard that looks like a working cache with no traffic.
                    .enableStatistics()
                    .build();
        }
    }

    /**
     * The no-Redis configuration. Every read goes to PostgreSQL and every eviction is a no-op,
     * which is exactly the behaviour the service must survive.
     */
    @Configuration
    @ConditionalOnProperty(name = "parcelflow.cache.enabled", havingValue = "false")
    static class NoCacheConfiguration {

        @Bean
        public CacheManager cacheManager() {
            log.info("Shipment tracking cache is disabled; every read goes to PostgreSQL");
            return new NoOpCacheManager();
        }
    }
}
