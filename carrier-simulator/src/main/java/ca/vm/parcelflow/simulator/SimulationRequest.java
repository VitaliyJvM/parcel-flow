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
 * @param scenario only {@link Scenario#NORMAL} exists in Stage 2; duplicate, delayed, out-of-order
 *     and invalid scenarios arrive in Stage 3
 */
public record SimulationRequest(
        UUID shipmentId,
        String trackingNumber,
        String carrierCode,
        Scenario scenario,
        Duration delayBetweenEvents,
        String correlationId) {

    public enum Scenario {
        NORMAL
    }

    public static final String USAGE = """
            Usage:
              java -jar carrier-simulator.jar \\
                --shipment-id=<uuid> \\
                --tracking-number=SP123456 \\
                --carrier=SWIFTPOST \\
                [--scenario=NORMAL] \\
                [--delay-ms=500] \\
                [--correlation-id=<id>]

            Carriers: SWIFTPOST, PACIFICA
            Scenarios: NORMAL (duplicate, out-of-order, delayed and invalid arrive in Stage 3)""";

    public static SimulationRequest parse(ApplicationArguments args) {
        UUID shipmentId = parseUuid(required(args, "shipment-id"));
        String trackingNumber = required(args, "tracking-number");
        String carrier = required(args, "carrier").trim().toUpperCase(Locale.ROOT);

        // Fail here rather than after connecting to Kafka and publishing half a journey.
        CarrierEventScript.forCarrier(carrier);

        Scenario scenario = parseScenario(optional(args, "scenario", Scenario.NORMAL.name()));
        Duration delay = parseDelay(optional(args, "delay-ms", "500"));
        String correlationId = optional(args, "correlation-id", UUID.randomUUID().toString());

        return new SimulationRequest(
                shipmentId, trackingNumber, carrier, scenario, delay, correlationId);
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

    private static Scenario parseScenario(String value) {
        try {
            return Scenario.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown --scenario '%s'. Supported: %s"
                            .formatted(value, List.of(Scenario.values())), e);
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
}
