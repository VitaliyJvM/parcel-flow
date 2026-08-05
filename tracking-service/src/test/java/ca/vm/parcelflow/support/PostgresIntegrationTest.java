package ca.vm.parcelflow.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for tests that need a real PostgreSQL instance.
 *
 * <p>The container is started once per JVM from a static initializer rather than managed by the
 * {@code @Testcontainers} JUnit extension. The extension scopes a static {@code @Container} to a
 * single test class, so every integration test class would pay a fresh container start; this way all
 * of them share one, and Spring's context cache keeps the application context warm alongside it.
 *
 * <p>{@code @ServiceConnection} wires the JDBC URL, user and password into the context — no
 * {@code @DynamicPropertySource} plumbing. Flyway then runs the real migrations against the real
 * engine, so the tests verify the migrations too, not just the entity mappings.
 */
public abstract class PostgresIntegrationTest {

    // Testcontainers 2.x dropped the self-referential type parameter, so this is no longer
    // PostgreSQLContainer<?>.
    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("parcelflow")
                    .withUsername("parcelflow")
                    .withPassword("parcelflow");

    static {
        POSTGRES.start();
    }
}
