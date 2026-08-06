package ca.vm.parcelflow.infrastructure.config;

import ca.vm.parcelflow.tracking.error.ProcessingErrorClassifier;
import ca.vm.parcelflow.tracking.messaging.CarrierTrackingEventMessage;
import java.util.Map;
import java.util.function.BiConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Retry and dead letter policy for the ingest listener.
 *
 * <p>The shape is: retry retryable failures a bounded number of times with exponential backoff,
 * then hand the record to the recoverer, which records it in PostgreSQL and publishes it to the
 * dead letter topic. Non-retryable failures skip the retries and go straight there.
 */
@Configuration
@EnableConfigurationProperties(EventProcessingProperties.class)
public class KafkaErrorHandlingConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(KafkaErrorHandlingConfiguration.class);

    /**
     * Publishes to the dead letter topic.
     *
     * <p>Spring's recoverer already stamps the original topic, partition, offset, exception class
     * and exception message into headers, which is exactly the metadata the brief asks for — so
     * this configures it rather than reimplementing it.
     *
     * <p>The template is built here rather than exposed as a bean. A second
     * {@code KafkaTemplate} bean would make Boot's {@code @ConditionalOnMissingBean}
     * autoconfiguration back off, silently removing the default template that application code and
     * tests inject. Nothing outside this method needs the dead letter producer, so nothing outside
     * this method should be able to see it.
     *
     * <p>It needs its own serializer because it has to handle two different value types. A record
     * that failed <em>processing</em> carries a deserialized {@link CarrierTrackingEventMessage};
     * a record that failed <em>deserialization</em> carries the raw {@code byte[]} that could not
     * be parsed. {@link DelegatingByTypeSerializer} picks per value type, so both reach the topic —
     * and the raw bytes are the more valuable of the two, since they are what an operator needs to
     * see to work out what the producer sent.
     *
     * <p>The destination resolver sends everything to partition -1, letting the broker choose. The
     * default keeps the source partition number, which breaks the moment the DLT is given fewer
     * partitions than the source topic.
     */
    @Bean
    public BiConsumer<ConsumerRecord<?, ?>, Exception> deadLetterPublisher(
            KafkaProperties kafkaProperties,
            KafkaConnectionDetails connectionDetails,
            CarrierEventTopicProperties topicProperties) {

        Map<String, Object> configs = kafkaProperties.buildProducerProperties();

        // The broker address must come from KafkaConnectionDetails, not from the raw properties.
        // Boot's own producer and consumer factories resolve it this way, and anything that reads
        // spring.kafka.bootstrap-servers directly silently ignores every connection-details source
        // — a Testcontainers @ServiceConnection, a cloud binding, a service registry — and connects
        // to whatever the static default happens to point at.
        configs.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, connectionDetails.getBootstrapServers());

        ProducerFactory<String, Object> producerFactory = new DefaultKafkaProducerFactory<>(
                configs,
                new StringSerializer(),
                new DelegatingByTypeSerializer(Map.of(
                        byte[].class, new ByteArraySerializer(),
                        CarrierTrackingEventMessage.class, new JacksonJsonSerializer<>())));

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                new KafkaTemplate<>(producerFactory),
                (record, exception) -> new TopicPartition(
                        topicProperties.carrierTrackingEventsDltTopic(), -1));

        // Off by default, which makes a dead letter the one event in the pipeline that leaves no
        // trace of its own. Turning it on means every message that leaves the main topic is
        // accounted for in the log as well as in the failed_events table.
        recoverer.setLogRecoveryRecord(true);
        return recoverer;
    }

    /**
     * The container's error handler.
     *
     * <p>The not-retryable list comes from {@link ProcessingErrorClassifier}, which is also what
     * classifies the failure for the stored record and the manual retry endpoint. One declaration,
     * so the retry policy and the reported category cannot disagree.
     */
    @Bean
    public CommonErrorHandler carrierTrackingEventErrorHandler(
            ConsumerRecordRecoverer failedEventRecoverer, EventProcessingProperties properties) {

        // Spring Framework 7 folded ExponentialBackOffWithMaxRetries into ExponentialBackOff:
        // maxAttempts bounds the number of backoff intervals, which is the number of retries.
        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setMaxAttempts(properties.maxKafkaRetries());
        backOff.setInitialInterval(properties.retryInitialBackoff().toMillis());
        backOff.setMultiplier(properties.retryBackoffMultiplier());
        backOff.setMaxInterval(properties.retryMaxBackoff().toMillis());
        // Jitter so that a broker hiccup affecting many records does not have every consumer
        // retrying in lockstep on the same millisecond.
        backOff.setJitter(properties.retryInitialBackoff().toMillis() / 2);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(failedEventRecoverer, backOff);

        // Permanently invalid messages skip the backoff entirely. Retrying them would consume the
        // full budget on every redelivery while having no chance of a different outcome.
        ProcessingErrorClassifier.NON_RETRYABLE_EXCEPTIONS
                .forEach(errorHandler::addNotRetryableExceptions);

        // Makes the retry visible rather than silent. Without this, a record being retried five
        // times looks identical in the log to one being processed once.
        errorHandler.setRetryListeners((record, exception, deliveryAttempt) ->
                log.warn("Retrying {}-{}@{} after {} (delivery attempt {})",
                        record.topic(), record.partition(), record.offset(),
                        exception.getClass().getSimpleName(), deliveryAttempt));

        // Log the commit-level failure once, at the point the record is given up on.
        errorHandler.setCommitRecovered(true);
        return errorHandler;
    }
}
