package ca.vm.parcelflow.simulator;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * The sequence of carrier-native event codes a carrier emits for a parcel that reaches its
 * destination without incident.
 *
 * <p>Each carrier speaks its own vocabulary — that is the whole point of the tracking service's
 * normalization layer, and the simulator would not exercise it if every carrier published the same
 * codes.
 *
 * <p>Scripts also carry a plausible gap between scans. The gap is scaled down to the requested
 * delay at publish time; it exists so the generated {@code eventTime} values look like a real
 * multi-day journey rather than eight timestamps one second apart.
 */
public enum CarrierEventScript {

    SWIFTPOST(List.of(
            new Step("SP_CREATED", "Rivermouth", "Shipping label created", Duration.ZERO),
            new Step("SP_PICKUP", "Rivermouth", "Parcel collected from shipper", Duration.ofHours(4)),
            new Step("SP_TRANSIT", "Rivermouth", "Departed origin facility", Duration.ofHours(3)),
            new Step("SP_DEPOT", "Calder Junction", "Arrived at sorting hub", Duration.ofHours(11)),
            new Step("SP_TRANSIT", "Calder Junction", "Departed sorting hub", Duration.ofHours(2)),
            new Step("SP_DEPOT", "Ashgrove", "Arrived at delivery depot", Duration.ofHours(9)),
            new Step("SP_OFD", "Ashgrove", "Out for delivery", Duration.ofHours(6)),
            new Step("SP_DELIVERED", "Ashgrove", "Delivered", Duration.ofHours(3)))),

    PACIFICA(List.of(
            new Step("MANIFESTED", "Port Halloway", "Shipment manifested", Duration.ZERO),
            new Step("COLLECTED", "Port Halloway", "Collected from consignor", Duration.ofHours(5)),
            new Step("MOVING", "Port Halloway", "In transit on line haul", Duration.ofHours(2)),
            new Step("AT_TERMINAL", "Middenvale", "Arrived at terminal", Duration.ofHours(14)),
            new Step("MOVING", "Middenvale", "Departed terminal", Duration.ofHours(3)),
            new Step("COURIER_ROUTE", "Selby Flats", "Assigned to courier route", Duration.ofHours(8)),
            new Step("COMPLETE", "Selby Flats", "Delivery complete", Duration.ofHours(4))));

    private final List<Step> steps;

    CarrierEventScript(List<Step> steps) {
        this.steps = List.copyOf(steps);
    }

    public List<Step> steps() {
        return steps;
    }

    public static CarrierEventScript forCarrier(String carrierCode) {
        String normalized = carrierCode == null ? "" : carrierCode.trim().toUpperCase(Locale.ROOT);
        for (CarrierEventScript script : values()) {
            if (script.name().equals(normalized)) {
                return script;
            }
        }
        throw new IllegalArgumentException(
                "The simulator has no event script for carrier '%s'. Supported: %s"
                        .formatted(carrierCode, List.of(values())));
    }

    /**
     * @param carrierEventType the carrier's own code, which the tracking service must normalize
     * @param location where the scan happened; fictional place names
     * @param description free text as a carrier would send it
     * @param elapsedSincePrevious plausible real-world gap before this scan
     */
    public record Step(
            String carrierEventType,
            String location,
            String description,
            Duration elapsedSincePrevious) {
    }
}
