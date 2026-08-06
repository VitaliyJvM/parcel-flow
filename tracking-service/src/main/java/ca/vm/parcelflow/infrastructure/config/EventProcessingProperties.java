package ca.vm.parcelflow.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Retry and cache policy.
 *
 * <p>Every bound in the reliability story is here rather than as a constant in the class that uses
 * it, because these are the numbers an operator tunes when a deployment behaves differently from a
 * laptop, and because keeping them together makes the total worst-case latency of a poisoned record
 * something you can compute by reading one file.
 */
@Validated
@ConfigurationProperties(prefix = "parcelflow.processing")
public record EventProcessingProperties(

        /*
         * Optimistic-lock retries inside the processor, before the record is handed back to Kafka.
         * Small on purpose: conflicts resolve on the first retry or they indicate sustained
         * contention that a tight loop will not fix.
         */
        @Min(0) int maxOptimisticLockRetries,

        /* Kafka-level re-deliveries of a retryable failure before dead-lettering. */
        @Min(0) int maxKafkaRetries,

        /* Delay before the first Kafka re-delivery. */
        Duration retryInitialBackoff,

        /* Multiplier applied to the backoff after each attempt. */
        @Positive double retryBackoffMultiplier,

        /* Ceiling on the backoff, so exponential growth cannot park a consumer thread for minutes. */
        Duration retryMaxBackoff,

        /* How long a cached shipment tracking response stays valid. */
        Duration shipmentCacheTtl) {
}
