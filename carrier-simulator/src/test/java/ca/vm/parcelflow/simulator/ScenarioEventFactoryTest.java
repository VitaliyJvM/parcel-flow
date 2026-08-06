package ca.vm.parcelflow.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * What each scenario actually puts on the topic.
 *
 * <p>No broker: the publisher's job is a loop and a {@code send}, while the interesting decisions —
 * which events, in what order, with which ids — all live here.
 */
class ScenarioEventFactoryTest {

    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");
    private static final UUID SHIPMENT_ID = UUID.fromString("00c5356b-8b0b-47e5-b88c-1e504dd2bf34");

    private final ScenarioEventFactory factory =
            new ScenarioEventFactory(Clock.fixed(NOW, ZoneOffset.UTC));

    @Nested
    @DisplayName("NORMAL")
    class Normal {

        @Test
        @DisplayName("sequence numbers start at 1 and increase by one, matching a carrier's scan count")
        void sequenceNumbersAreMonotonicFromOne() {
            List<CarrierTrackingEventMessage> events = factory.eventsFor(request(Scenario.NORMAL));

            assertThat(events).isNotEmpty();
            for (int i = 0; i < events.size(); i++) {
                assertThat(events.get(i).sequenceNumber()).isEqualTo(i + 1L);
            }
        }

        @Test
        @DisplayName("event times increase and the journey ends at the current instant")
        void eventTimesTellAPlausibleStory() {
            List<CarrierTrackingEventMessage> events = factory.eventsFor(request(Scenario.NORMAL));

            assertThat(events)
                    .extracting(CarrierTrackingEventMessage::eventTime)
                    .isSortedAccordingTo(Comparator.naturalOrder());
            assertThat(events.getLast().eventTime()).isEqualTo(NOW);
            assertThat(Duration.between(events.getFirst().eventTime(), events.getLast().eventTime()))
                    .isGreaterThan(Duration.ofHours(24));
        }

        @Test
        @DisplayName("every event carries a unique id, the shared correlation id, and schema version 1")
        void identifiersAreCorrect() {
            List<CarrierTrackingEventMessage> events = factory.eventsFor(request(Scenario.NORMAL));

            assertThat(events).extracting(CarrierTrackingEventMessage::eventId).doesNotHaveDuplicates();
            assertThat(events).allSatisfy(event -> {
                assertThat(event.schemaVersion()).isEqualTo(1);
                assertThat(event.shipmentId()).isEqualTo(SHIPMENT_ID);
                assertThat(event.trackingNumber()).isEqualTo("SP-1");
                assertThat(event.correlationId()).isEqualTo("corr-1");
                assertThat(event.location()).isNotBlank();
                assertThat(event.description()).isNotBlank();
            });
        }

        @Test
        @DisplayName("carrier-native codes are published, never normalized ones")
        void publishesCarrierNativeCodes() {
            assertThat(factory.eventsFor(request(Scenario.NORMAL, "SWIFTPOST")))
                    .extracting(CarrierTrackingEventMessage::eventType)
                    .startsWith("SP_CREATED")
                    .endsWith("SP_DELIVERED")
                    // If the simulator emitted normalized values the tracking service's
                    // normalization layer would never be exercised end to end.
                    .doesNotContain("LABEL_CREATED", "DELIVERED");

            assertThat(factory.eventsFor(request(Scenario.NORMAL, "PACIFICA")))
                    .extracting(CarrierTrackingEventMessage::eventType)
                    .startsWith("MANIFESTED")
                    .endsWith("COMPLETE")
                    .doesNotContain("LABEL_CREATED", "DELIVERED");
        }
    }

    @Nested
    @DisplayName("reproducibility")
    class Reproducibility {

        @ParameterizedTest
        @EnumSource(Scenario.class)
        @DisplayName("the same seed produces byte-identical events for every scenario")
        void sameSeedSameEvents(Scenario scenario) {
            assertThat(factory.eventsFor(request(scenario)))
                    .isEqualTo(factory.eventsFor(request(scenario)));
        }

        @Test
        @DisplayName("a different seed produces different event ids")
        void differentSeedDifferentIds() {
            List<UUID> first = factory.eventsFor(request(Scenario.NORMAL, "SWIFTPOST", 1L))
                    .stream().map(CarrierTrackingEventMessage::eventId).toList();
            List<UUID> second = factory.eventsFor(request(Scenario.NORMAL, "SWIFTPOST", 2L))
                    .stream().map(CarrierTrackingEventMessage::eventId).toList();

            assertThat(first).doesNotContainAnyElementsOf(second);
        }
    }

    @Nested
    @DisplayName("DUPLICATE")
    class Duplicate {

        @Test
        @DisplayName("republished events reuse the same event id, as a real redelivery would")
        void duplicatesShareTheEventId() {
            List<CarrierTrackingEventMessage> normal = factory.eventsFor(request(Scenario.NORMAL));
            List<CarrierTrackingEventMessage> duplicated =
                    factory.eventsFor(request(Scenario.DUPLICATE));

            assertThat(duplicated).hasSizeGreaterThan(normal.size());

            // The set of distinct events is unchanged; only the delivery count differs. A fresh id
            // would make them different events describing the same scan, which is a different
            // problem entirely.
            assertThat(duplicated).containsAll(normal);
            assertThat(duplicated.stream().distinct().toList())
                    .containsExactlyInAnyOrderElementsOf(normal);
        }

        @Test
        @DisplayName("a duplicate is byte-identical to its original, not merely similar")
        void duplicateIsIdentical() {
            List<CarrierTrackingEventMessage> events =
                    factory.eventsFor(request(Scenario.DUPLICATE));

            assertThat(events.get(0)).isEqualTo(events.get(1));
        }
    }

    @Nested
    @DisplayName("OUT_OF_ORDER")
    class OutOfOrder {

        @Test
        @DisplayName("the same events are published, but not in sequence order")
        void reordersWithoutLosingEvents() {
            List<CarrierTrackingEventMessage> normal = factory.eventsFor(request(Scenario.NORMAL));
            List<CarrierTrackingEventMessage> shuffled =
                    factory.eventsFor(request(Scenario.OUT_OF_ORDER));

            assertThat(shuffled).containsExactlyInAnyOrderElementsOf(normal);

            List<Long> sequences =
                    shuffled.stream().map(CarrierTrackingEventMessage::sequenceNumber).toList();
            assertThat(sequences)
                    .as("the whole point of the scenario is that arrival order != sequence order")
                    .isNotEqualTo(sequences.stream().sorted().toList());
        }

        @Test
        @DisplayName("the first and last events stay in place, so the parcel still ends DELIVERED")
        void endpointsArePreserved() {
            List<CarrierTrackingEventMessage> normal = factory.eventsFor(request(Scenario.NORMAL));
            List<CarrierTrackingEventMessage> shuffled =
                    factory.eventsFor(request(Scenario.OUT_OF_ORDER));

            assertThat(shuffled.getFirst()).isEqualTo(normal.getFirst());
            assertThat(shuffled.getLast()).isEqualTo(normal.getLast());
        }
    }

    @Nested
    @DisplayName("failure scenarios")
    class FailureScenarios {

        @Test
        @DisplayName("INVALID_EVENT breaks validation in two distinct ways")
        void invalidEventIsInvalid() {
            List<CarrierTrackingEventMessage> events =
                    factory.eventsFor(request(Scenario.INVALID_EVENT));

            assertThat(events).hasSize(1);
            assertThat(events.getFirst().schemaVersion()).isNotEqualTo(1);
            assertThat(events.getFirst().correlationId()).isBlank();
        }

        @Test
        @DisplayName("UNKNOWN_CARRIER_EVENT is structurally valid but carries an unmapped code")
        void unknownEventTypeIsOtherwiseValid() {
            List<CarrierTrackingEventMessage> events =
                    factory.eventsFor(request(Scenario.UNKNOWN_CARRIER_EVENT));

            assertThat(events).hasSize(1);
            CarrierTrackingEventMessage event = events.getFirst();
            // Everything the validator checks is fine; only normalization can reject it. That
            // separation is what makes the two failure categories distinguishable.
            assertThat(event.schemaVersion()).isEqualTo(1);
            assertThat(event.correlationId()).isNotBlank();
            assertThat(event.sequenceNumber()).isPositive();
            assertThat(event.eventType()).isEqualTo("SP_TELEPORTED");
        }

        @Test
        @DisplayName("RAPID_CONCURRENT_EVENTS publishes the same events as NORMAL; only pacing differs")
        void rapidIsNormalWithoutDelay() {
            assertThat(factory.eventsFor(request(Scenario.RAPID_CONCURRENT_EVENTS)))
                    .isEqualTo(factory.eventsFor(request(Scenario.NORMAL)));
        }
    }

    private static SimulationRequest request(Scenario scenario) {
        return request(scenario, "SWIFTPOST", 42L);
    }

    private static SimulationRequest request(Scenario scenario, String carrier) {
        return request(scenario, carrier, 42L);
    }

    private static SimulationRequest request(Scenario scenario, String carrier, long seed) {
        return new SimulationRequest(
                SHIPMENT_ID, "SP-1", carrier, scenario, Duration.ZERO, "corr-1", seed);
    }
}
