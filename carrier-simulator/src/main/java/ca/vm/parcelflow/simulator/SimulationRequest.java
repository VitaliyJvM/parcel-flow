package ca.vm.parcelflow.simulator;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;

/**
 * The parsed command line.
 *
 * <p>Parsing is explicit rather than bound to {@code @ConfigurationProperties} so that a typo in an
 * argument fails immediately with a usage message, instead of silently falling back to a default
 * and publishing events for the wrong parcel.
 *
 * @param seed makes a run reproducible. Every identifier the simulator generates comes from a
 *     generator seeded with this value, so the same command produces byte-identical events —
 *     which is what lets a failure be re-run rather than merely described.
 */
public record SimulationRequest(
        UUID shipmentId,
        String trackingNumber,
        String carrierCode,
        Scenario scenario,
        Duration delayBetweenEvents,
        String correlationId,
        long seed) {

    public static final String USAGE = """
            Usage:
              java -jar carrier-simulator.jar \\
                --shipment-id=<uuid> \\
                --tracking-number=SP123456 \\
                --carrier=SWIFTPOST \\
                [--scenario=NORMAL] \\
                [--delay-ms=500] \\
                [--seed=42] \\
                [--correlation-id=<id>]

            Carriers:  SWIFTPOST, PACIFICA

            Scenarios:
              NORMAL                  Publishes the carrier's full journey once.
              DUPLICATE               Republishes some events verbatim to exercise idempotency.
              OUT_OF_ORDER            Publishes the journey with the middle events reordered.
              INVALID_EVENT           Publishes an event that fails validation.
              UNKNOWN_CARRIER_EVENT   Publishes an event whose carrier code has no mapping.
              RAPID_CONCURRENT_EVENTS Publishes the journey with no delay between events.

            Pass --seed to make a run reproducible: the same seed generates the same event ids.""";

    public static SimulationRequest parse(ApplicationArguments args) {
        UUID shipmentId = parseUuid(required(args, "shipment-id"));
        String trackingNumber = required(args, "tracking-number");
        String carrier = required(args, "carrier").trim().toUpperCase(Locale.ROOT);

        // Fail here rather than after connecting to Kafka and publishing half a journey.
        CarrierEventScript.forCarrier(carrier);

        Scenario scenario = Scenario.parse(optional(args, "scenario", Scenario.NORMAL.name()));
        Duration delay = parseDelay(optional(args, "delay-ms", "500"));
        long seed = parseSeed(optional(args, "seed", null));
        String correlationId = optional(args, "correlation-id", null);

        return new SimulationRequest(
                shipmentId, trackingNumber, carrier, scenario, delay, correlationId, seed);
    }

    private static String required(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty() || values.getFirst().isBlank()) {
            throw new IllegalArgumentException("Missing required argument --%s".formatted(name));
        }
        return values.getFirst().trim();
    }

    private static String optional(ApplicationArguments args, String name, String fallback) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() || values.getFirst().isBlank()
                ? fallback
                : values.getFirst().trim();
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "--shipment-id must be a UUID, got '%s'".formatted(value), e);
        }
    }

    private static Duration parseDelay(String value) {
        long millis;
        try {
            millis = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "--delay-ms must be a whole number of milliseconds, got '%s'".formatted(value), e);
        }
        if (millis < 0) {
            throw new IllegalArgumentException("--delay-ms must not be negative");
        }
        return Duration.ofMillis(millis);
    }

    /**
     * Falls back to a random seed rather than a fixed one. A fixed default would make every run
     * generate the same event ids, and the second run against the same broker would then be
     * silently deduplicated as a replay of the first.
     */
    private static long parseSeed(String value) {
        if (value == null) {
            return new java.security.SecureRandom().nextLong();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "--seed must be a whole number, got '%s'".formatted(value), e);
        }
    }
}
