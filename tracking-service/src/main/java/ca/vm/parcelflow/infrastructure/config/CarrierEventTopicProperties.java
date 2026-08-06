package ca.vm.parcelflow.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Topic and consumer settings that belong to ParcelFlow rather than to Spring Kafka.
 *
 * <p>Kept out of the {@code spring.kafka.*} namespace deliberately: those are the framework's
 * properties, and mixing application settings into them makes it unclear which values Spring reads
 * and which the application reads. Topic names are configuration, not constants, because the same
 * image runs against differently-named topics per environment.
 */
@Validated
@ConfigurationProperties(prefix = "parcelflow.kafka")
public record CarrierEventTopicProperties(

        /* Consumer group for the tracking service ingest listener. */
        @NotBlank String consumerGroup,

        /* Partitions to create the ingest topic with, when the service creates it. */
        @Positive int partitions,

        /* Replication factor. 1 is correct for the single-broker development stack only. */
        @Positive short replicationFactor,

        @NotBlank String carrierTrackingEventsTopic,

        /*
         * Dead letter topic. Created in Stage 2 so the topology is complete and operators can see
         * it; nothing publishes to it until Stage 3 adds the retry and recovery policy.
         */
        @NotBlank String carrierTrackingEventsDltTopic) {
}
