package ca.vm.parcelflow.tracking.loadtest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An HTTP door onto the carrier events topic, for load generation only.
 *
 * <p><b>Why this exists.</b> The only producer in the project is {@code carrier-simulator}, a CLI
 * that speaks the Kafka protocol. A load generator that has to shell out to a JVM per batch cannot
 * hold a request rate, and driving Kafka from k6 needs a custom binary built with an xk6 extension.
 * This endpoint lets the load test publish over HTTP while the events still travel the real path:
 * same topic, same partition key, same deserializer, same consumer, same database. Nothing about
 * the measurement is short-circuited except who called {@code send()}.
 *
 * <p><b>Disabled by default.</b> {@code parcelflow.load-testing.enabled} must be set to true, which
 * the standard Compose stack does not do. Enabled, this endpoint can inject arbitrary events for
 * any parcel with no authentication — it is a test harness, and a real deployment would not ship
 * it at all. The {@code /internal} prefix exists so the whole subtree can be blocked at the ingress
 * with one rule.
 *
 * <p><b>Pass-through, not validated.</b> The body is forwarded to the topic as written. That is
 * deliberate: a load test has to be able to publish malformed events to exercise the dead letter
 * path, and a request that this endpoint rejected would never reach the consumer that is supposed
 * to reject it. The only field read is {@code shipmentId}, and only to key the record so one
 * parcel's events keep their per-partition ordering — the same key the simulator uses.
 */
@RestController
@RequestMapping("/internal/load/carrier-events")
@ConditionalOnProperty(name = "parcelflow.load-testing.enabled", havingValue = "true")
@Tag(name = "Load testing",
        description = "Publishes raw carrier events to Kafka. Disabled unless "
                + "parcelflow.load-testing.enabled is true; never enable it outside a load test.")
public class CarrierEventLoadController {

    private static final Logger log = LoggerFactory.getLogger(CarrierEventLoadController.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;
    private final String topic;

    public CarrierEventLoadController(
            KafkaTemplate<String, String> kafkaTemplate,
            JsonMapper jsonMapper,
            @Value("${parcelflow.kafka.carrier-tracking-events-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.jsonMapper = jsonMapper;
        this.topic = topic;
        log.warn("Load-testing publish endpoint is ENABLED at /internal/load/carrier-events. "
                + "This accepts unauthenticated writes to the carrier events topic.");
    }

    /**
     * Publishes one event, or an array of events in one request.
     *
     * <p>Batching in the request rather than one HTTP call per event is what makes an event rate of
     * a few thousand per second reachable from a load generator without the measurement turning
     * into a measurement of HTTP overhead.
     *
     * <p>Returns 202: the broker has accepted the records, the consumer has not processed them.
     * Reporting 200 would imply an end-to-end guarantee this endpoint cannot make, and the whole
     * point of the load test is to measure the lag between those two facts.
     */
    @PostMapping
    @Operation(summary = "Publish raw carrier events to the ingest topic (load testing only)")
    public ResponseEntity<PublishAck> publish(@RequestBody JsonNode body) {
        List<JsonNode> events = new ArrayList<>();
        if (body.isArray()) {
            events.addAll(body.values());
        } else {
            events.add(body);
        }

        for (JsonNode event : events) {
            String key = event.path("shipmentId").asString(null);
            kafkaTemplate.send(topic, key, jsonMapper.writeValueAsString(event));
        }

        // One flush per request, not per record: the send calls above are asynchronous, and
        // returning before the batch is on the wire would let the load generator run ahead of the
        // producer's buffer and report throughput the broker never saw.
        kafkaTemplate.flush();

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new PublishAck(events.size(), topic));
    }

    /** @param published the number of records handed to the broker */
    public record PublishAck(int published, String topic) {
    }
}
