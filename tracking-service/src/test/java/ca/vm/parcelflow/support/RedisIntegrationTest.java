package ca.vm.parcelflow.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;

/**
 * Base class for tests that need a real Redis as well as a real database.
 *
 * <p>Testcontainers 2.x has no Redis module, so this is a plain {@code GenericContainer}. Boot's
 * {@code RedisContainerConnectionDetailsFactory} accepts any container named {@code redis}, which
 * is what {@code @ServiceConnection(name = "redis")} declares.
 *
 * <p>Started once per JVM from a static initializer, for the same reason as
 * {@link PostgresIntegrationTest}.
 */
@TestPropertySource(properties = {
        // The suite runs with the cache off by default; these classes are the ones that assert
        // caching behaviour, so they turn it on and pay for a container.
        "parcelflow.cache.enabled=true",
        "management.health.redis.enabled=true"
})
public abstract class RedisIntegrationTest extends PostgresIntegrationTest {

    private static final int REDIS_PORT = 6379;

    @ServiceConnection(name = "redis")
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(REDIS_PORT);

    static {
        REDIS.start();
    }
}
