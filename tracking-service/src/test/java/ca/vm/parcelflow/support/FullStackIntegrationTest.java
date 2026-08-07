package ca.vm.parcelflow.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;

/**
 * Base class for tests that need every backing service at once: PostgreSQL, Redpanda and Redis.
 *
 * <p>Only the operational-surface tests need this. Health, readiness and the scrape endpoint are
 * statements about the whole deployment — readiness is DOWN unless the broker is genuinely
 * reachable — so asserting them against a context with two thirds of its dependencies missing would
 * prove nothing.
 *
 * <p>Extends {@link KafkaIntegrationTest} and adds Redis rather than the other way round because
 * Java has single inheritance and the Redis container is the smaller of the two additions. Every
 * container is a per-JVM singleton started from a static initializer, so a test class that needs
 * all three pays for whichever ones another class has not already started.
 */
@TestPropertySource(properties = {
        // The suite runs with the cache off by default. These tests turn it on, because a health
        // endpoint that never reports on Redis cannot demonstrate that Redis is excluded from
        // readiness on purpose.
        "parcelflow.cache.enabled=true",
        "management.health.redis.enabled=true"
})
public abstract class FullStackIntegrationTest extends KafkaIntegrationTest {

    private static final int REDIS_PORT = 6379;

    @ServiceConnection(name = "redis")
    protected static final GenericContainer<?> FULL_STACK_REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(REDIS_PORT);

    static {
        FULL_STACK_REDIS.start();
    }
}
