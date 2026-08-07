package ca.vm.parcelflow.infrastructure.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * The set of log fields ParcelFlow attaches to a unit of work, and the scope they live in.
 *
 * <p>Field names are declared once here because they are a contract with the log store, not an
 * implementation detail: {@code docs/operations.md} documents searches against these exact keys,
 * the structured encoder renders them as top-level JSON members, and a rename that touched only one
 * call site would silently break every saved query. Nothing outside this class writes to the MDC.
 *
 * <p><b>Scoped, not set-and-forget.</b> Consumer threads and servlet threads are pooled and
 * reused. An MDC entry left behind by one record reappears on the next one — which is worse than no
 * context at all, because it attributes work to the wrong parcel. {@link #close()} restores the
 * previous value of every key it touched rather than clearing it, so nesting works.
 *
 * <p><b>What is deliberately absent.</b> No customer identifier, no address, no recipient name, no
 * raw payload. The identifiers here are opaque keys that resolve to a row an operator has to be
 * authorized to read; that is what makes it safe to ship these logs somewhere and index them.
 */
public final class LogContext implements AutoCloseable {

    public static final String CORRELATION_ID = "correlationId";
    public static final String EVENT_ID = "eventId";
    public static final String SHIPMENT_ID = "shipmentId";
    public static final String CARRIER_CODE = "carrierCode";
    public static final String TOPIC = "topic";
    public static final String PARTITION = "partition";
    public static final String OFFSET = "offset";

    /** Previous values, so close() restores rather than clears. Null means "was not set". */
    private final Map<String, String> previous;

    private LogContext(Map<String, String> values) {
        this.previous = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (value != null) {
                previous.put(key, MDC.get(key));
                MDC.put(key, value);
            }
        });
    }

    /** The full context for one carrier event taken off the topic. */
    public static LogContext forCarrierEvent(
            String correlationId,
            UUID eventId,
            UUID shipmentId,
            String carrierCode,
            String topic,
            int partition,
            long offset) {

        Map<String, String> values = new LinkedHashMap<>();
        values.put(CORRELATION_ID, correlationId);
        values.put(EVENT_ID, asString(eventId));
        values.put(SHIPMENT_ID, asString(shipmentId));
        values.put(CARRIER_CODE, carrierCode);
        values.put(TOPIC, topic);
        values.put(PARTITION, String.valueOf(partition));
        values.put(OFFSET, String.valueOf(offset));
        return new LogContext(values);
    }

    /** The context for an inbound HTTP request, which carries only a correlation id. */
    public static LogContext forRequest(String correlationId) {
        return new LogContext(Map.of(CORRELATION_ID, correlationId));
    }

    /**
     * Returns the supplied correlation id, or a fresh one when the producer did not send a usable
     * one.
     *
     * <p>Generating rather than logging {@code null} is the point of the method. A missing
     * correlation id is exactly the situation in which someone is trying to trace something
     * unusual, and "the field is empty on the interesting records" is the least useful possible
     * behaviour. The generated id still ties together every line produced for that one unit of
     * work, which is most of the value; what is lost is only the link back to the producer.
     */
    public static String correlationIdOrNew(String candidate) {
        return candidate == null || candidate.isBlank() ? UUID.randomUUID().toString() : candidate;
    }

    private static String asString(UUID value) {
        return value == null ? null : value.toString();
    }

    @Override
    public void close() {
        previous.forEach((key, value) -> {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        });
    }
}
