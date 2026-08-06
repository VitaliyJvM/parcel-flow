package ca.vm.parcelflow.infrastructure.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topics the service depends on.
 *
 * <p>Spring's {@code KafkaAdmin} creates any {@link NewTopic} bean that does not already exist, at
 * startup and before listeners begin consuming. This is preferred over relying on the broker's
 * auto-create: auto-created topics take the broker's default partition count, so the partitioning
 * that per-shipment ordering depends on would be silently wrong. Declaring them also means the
 * topology is visible in source rather than in someone's shell history.
 *
 * <p>Creating a topic that exists is a no-op, so this is safe on every restart.
 */
@Configuration
@EnableConfigurationProperties(CarrierEventTopicProperties.class)
public class KafkaTopicConfiguration {

    private final CarrierEventTopicProperties properties;

    public KafkaTopicConfiguration(CarrierEventTopicProperties properties) {
        this.properties = properties;
    }

    /**
     * Multiple partitions on purpose. Producers key every event by shipment id, so all events for
     * one parcel land on one partition and are therefore delivered to one consumer in the order
     * they were produced. Ordering is guaranteed per parcel, never globally — which is exactly the
     * guarantee the domain needs, and why the out-of-order rules exist for everything else.
     */
    @Bean
    public NewTopic carrierTrackingEventsTopic() {
        return TopicBuilder.name(properties.carrierTrackingEventsTopic())
                .partitions(properties.partitions())
                .replicas(properties.replicationFactor())
                .build();
    }

    @Bean
    public NewTopic carrierTrackingEventsDeadLetterTopic() {
        return TopicBuilder.name(properties.carrierTrackingEventsDltTopic())
                .partitions(properties.partitions())
                .replicas(properties.replicationFactor())
                .build();
    }
}
