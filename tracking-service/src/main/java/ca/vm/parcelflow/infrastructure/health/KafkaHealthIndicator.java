package ca.vm.parcelflow.infrastructure.health;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.stereotype.Component;

/**
 * Reports whether the broker is reachable, so readiness can depend on it.
 *
 * <p>Spring Boot ships health contributors for the datasource and Redis but not for a Kafka client,
 * and readiness without one would be a lie: this service's primary job is consuming a topic, and an
 * instance that cannot reach the broker is not ready to do it however healthy its HTTP port looks.
 *
 * <p><b>{@code describeCluster}, not a topic read.</b> The lightest call that proves an actual
 * round trip to a broker — it returns the cluster id and the node list without touching consumer
 * group state, so a health check cannot perturb the thing it is checking.
 *
 * <p><b>The timeout is the whole design.</b> A health endpoint that blocks is worse than one that
 * reports DOWN: Kubernetes-style probes have their own deadline, and a probe that hangs is
 * indistinguishable from a hung process. The admin client is given a short, bounded window and a
 * timeout is reported as DOWN with the timeout as the reason.
 *
 * <p>One long-lived {@link AdminClient} rather than one per check. Creating an admin client per
 * probe means a full connection setup every few seconds, and under a broker outage it means a pile
 * of clients all retrying their bootstrap at once.
 */
@Component
public class KafkaHealthIndicator implements HealthIndicator, DisposableBean {

    private final AdminClient adminClient;
    private final Duration timeout;

    public KafkaHealthIndicator(
            KafkaProperties kafkaProperties,
            KafkaConnectionDetails connectionDetails,
            @Value("${parcelflow.health.kafka-timeout-ms:1500}") long timeoutMillis) {

        Map<String, Object> configs = kafkaProperties.buildAdminProperties();
        // The broker address comes from KafkaConnectionDetails, not the raw properties, so a
        // Testcontainers @ServiceConnection or any other connection-details source is honoured
        // rather than silently ignored in favour of the static default.
        configs.put("bootstrap.servers", connectionDetails.getBootstrapServers());
        // Do not let the admin client's own retry loop outlive the health check's deadline.
        configs.put("default.api.timeout.ms", (int) timeoutMillis);
        configs.put("request.timeout.ms", (int) timeoutMillis);

        this.adminClient = AdminClient.create(configs);
        this.timeout = Duration.ofMillis(timeoutMillis);
    }

    @Override
    public Health health() {
        try {
            DescribeClusterResult cluster = adminClient.describeCluster(
                    new DescribeClusterOptions().timeoutMs((int) timeout.toMillis()));

            String clusterId = cluster.clusterId().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            int nodeCount = cluster.nodes().get(timeout.toMillis(), TimeUnit.MILLISECONDS).size();

            return Health.up()
                    .withDetail("clusterId", clusterId == null ? "unknown" : clusterId)
                    .withDetail("nodes", nodeCount)
                    .build();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.down(e).build();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            // The message, not the stack trace. A broker that is down produces this on every
            // probe, and a stack trace per probe buries everything else in the log.
            return Health.down()
                    .withDetail("error", e.getClass().getSimpleName())
                    .withDetail("message", String.valueOf(e.getMessage()))
                    .build();
        }
    }

    @Override
    public void destroy() {
        adminClient.close(Duration.ofSeconds(1));
    }
}
