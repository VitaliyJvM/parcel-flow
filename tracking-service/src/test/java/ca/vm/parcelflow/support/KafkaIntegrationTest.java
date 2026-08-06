package ca.vm.parcelflow.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * Base class for tests that need a real broker as well as a real database.
 *
 * <p>Redpanda rather than Apache Kafka: it speaks the same protocol, needs no ZooKeeper or KRaft
 * bootstrap, and starts in about a second, which keeps a broker-backed test cheap enough to run on
 * every build. The client library, the serializers and the consumer semantics under test are
 * identical either way — the same image is used in Docker Compose, so tests and the local stack
 * exercise the same broker.
 *
 * <p>Started once per JVM from a static initializer, for the same reason as
 * {@link PostgresIntegrationTest}: the JUnit extension would scope it per test class.
 */
public abstract class KafkaIntegrationTest extends PostgresIntegrationTest {

    @ServiceConnection
    protected static final RedpandaContainer REDPANDA =
            new RedpandaContainer("redpandadata/redpanda:v25.1.1");

    static {
        REDPANDA.start();
    }
}
