package ca.vm.parcelflow.simulator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns a {@link SimulationRequest} into the exact list of messages to publish.
 *
 * <p>Pure: same request in, same events out. Everything random is drawn from a generator seeded
 * with {@link SimulationRequest#seed()}, including event ids, so a run can be reproduced exactly
 * rather than approximately. Separated from the publisher so the interesting logic — what each
 * scenario actually does to the stream — is testable without a broker.
 */
@Component
public class ScenarioEventFactory {

    private final Clock clock;

    public ScenarioEventFactory(Clock clock) {
        this.clock = clock;
    }

    public List<CarrierTrackingEventMessage> eventsFor(SimulationRequest request) {
        Random random = new Random(request.seed());
        String correlationId = request.correlationId() != null
                ? request.correlationId()
                : deterministicUuid(random).toString();

        List<CarrierTrackingEventMessage> journey = journeyFor(request, random, correlationId);

        return switch (request.scenario()) {
            case NORMAL, RAPID_CONCURRENT_EVENTS -> journey;
            case DUPLICATE -> withDuplicates(journey);
            case OUT_OF_ORDER -> withMiddleReordered(journey, random);
            case INVALID_EVENT -> List.of(invalidEvent(request, random, correlationId));
            case UNKNOWN_CARRIER_EVENT -> List.of(unknownEventType(request, random, correlationId));
        };
    }

    /**
     * The carrier's normal journey.
     *
     * <p>Event times are back-dated across the script's plausible gaps so the sequence reads as a
     * real multi-day journey ending now, rather than a burst of identical timestamps.
     */
    private List<CarrierTrackingEventMessage> journeyFor(
            SimulationRequest request, Random random, String correlationId) {

        List<CarrierEventScript.Step> steps =
                CarrierEventScript.forCarrier(request.carrierCode()).steps();

        Duration totalJourney = steps.stream()
                .map(CarrierEventScript.Step::elapsedSincePrevious)
                .reduce(Duration.ZERO, Duration::plus);

        Instant cursor = clock.instant().minus(totalJourney);

        List<CarrierTrackingEventMessage> events = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            CarrierEventScript.Step step = steps.get(i);
            cursor = cursor.plus(step.elapsedSincePrevious());

            events.add(new CarrierTrackingEventMessage(
                    deterministicUuid(random),
                    CarrierTrackingEventMessage.SCHEMA_VERSION,
                    request.shipmentId(),
                    request.trackingNumber(),
                    request.carrierCode(),
                    step.carrierEventType(),
                    cursor,
                    // Sequence numbers start at 1: the contract requires a positive value, and
                    // carriers count scans from one.
                    i + 1L,
                    step.location(),
                    step.description(),
                    correlationId));
        }
        return events;
    }

    /**
     * Republishes every second event immediately after itself, byte for byte.
     *
     * <p>The same {@code eventId}, deliberately. That is what a Kafka redelivery after a rebalance
     * looks like, and what a carrier's own retry looks like. Generating a fresh id would make them
     * different events that merely describe the same scan — a different problem, and one the
     * ordering rules rather than the idempotency rules handle.
     */
    private List<CarrierTrackingEventMessage> withDuplicates(
            List<CarrierTrackingEventMessage> journey) {

        List<CarrierTrackingEventMessage> events = new ArrayList<>();
        for (int i = 0; i < journey.size(); i++) {
            events.add(journey.get(i));
            if (i % 2 == 0) {
                events.add(journey.get(i));
            }
        }
        return events;
    }

    /**
     * Shuffles the middle of the journey, leaving the first and last events in place.
     *
     * <p>The endpoints stay put so the run remains meaningful: the shipment must still finish
     * DELIVERED, which is the assertion that proves the reordering did not corrupt the outcome.
     * Shuffling everything would make a delivered-then-shuffled parcel indistinguishable from a
     * broken one.
     */
    private List<CarrierTrackingEventMessage> withMiddleReordered(
            List<CarrierTrackingEventMessage> journey, Random random) {

        if (journey.size() < 4) {
            return journey;
        }
        List<CarrierTrackingEventMessage> events = new ArrayList<>(journey);
        List<CarrierTrackingEventMessage> middle = events.subList(1, events.size() - 1);
        Collections.shuffle(middle, random);
        return events;
    }

    /**
     * An event that cannot pass validation: an unsupported schema version, and a blank correlation
     * id. Two violations rather than one, so the recorded error message shows the validator
     * reporting everything wrong with a message at once.
     */
    private CarrierTrackingEventMessage invalidEvent(
            SimulationRequest request, Random random, String correlationId) {

        return new CarrierTrackingEventMessage(
                deterministicUuid(random),
                99,
                request.shipmentId(),
                request.trackingNumber(),
                request.carrierCode(),
                CarrierEventScript.forCarrier(request.carrierCode()).steps().getFirst()
                        .carrierEventType(),
                clock.instant(),
                1L,
                "Nowhere",
                "Deliberately invalid event from the simulator",
                "  ");
    }

    /** A structurally valid event carrying a carrier code the normalizer has never heard of. */
    private CarrierTrackingEventMessage unknownEventType(
            SimulationRequest request, Random random, String correlationId) {

        return new CarrierTrackingEventMessage(
                deterministicUuid(random),
                CarrierTrackingEventMessage.SCHEMA_VERSION,
                request.shipmentId(),
                request.trackingNumber(),
                request.carrierCode(),
                "SP_TELEPORTED",
                clock.instant(),
                1L,
                "Nowhere",
                "Event code the carrier never documented",
                correlationId);
    }

    /**
     * A UUID drawn from the seeded generator rather than {@code UUID.randomUUID()}, which reads
     * from the system entropy source and would make {@code --seed} a lie.
     */
    private UUID deterministicUuid(Random random) {
        return new UUID(random.nextLong(), random.nextLong());
    }
}
