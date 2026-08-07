package ca.vm.parcelflow.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Registry-wide metric policy.
 *
 * <p>Two things belong here rather than at individual call sites: the tags every meter carries, and
 * the cardinality limit that applies to meters this codebase does not own.
 */
@Configuration
@EnableScheduling
public class ObservabilityConfiguration {

    /**
     * Tags applied to every meter in the registry.
     *
     * <p>{@code application} and {@code environment} are what let one Prometheus and one Grafana
     * dashboard serve more than one deployment without the series colliding. They are added in the
     * application rather than as Prometheus scrape labels so that the same values reach any other
     * registry the service might later publish to.
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTags(
            @Value("${spring.application.name}") String applicationName,
            @Value("${parcelflow.metrics.environment:local}") String environment) {
        return registry -> registry.config().commonTags(
                "application", applicationName,
                "environment", environment);
    }

    /**
     * A hard ceiling on how many time series any single meter name can produce.
     *
     * <p>ParcelFlow's own meters are tagged only with bounded dimensions, so this does nothing to
     * them. It is a guard against the meters this code does not control: {@code http.server.requests}
     * is tagged with {@code uri}, and one unmapped path pattern — or one 404-scanning bot — turns
     * that into a series per URL. Micrometer's answer to hitting the limit is to stop registering
     * new tag combinations, which loses some data; the alternative is a Prometheus server that runs
     * out of memory and loses all of it.
     */
    @Bean
    public MeterFilter cardinalityLimit(
            @Value("${parcelflow.metrics.max-series-per-meter:200}") int maxSeries) {
        return MeterFilter.maximumAllowableTags(
                "http.server.requests", "uri", maxSeries, MeterFilter.deny());
    }
}
