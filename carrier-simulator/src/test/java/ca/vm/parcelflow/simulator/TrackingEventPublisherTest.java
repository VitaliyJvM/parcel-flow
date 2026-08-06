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
import org.junit.jupiter.api.Test;

/**
 * Event generation only. Publishing to a broker is covered by the tracking service's Kafka
 * integration test, which asserts the far more useful property: that what this produces is
 * something the consumer can actually read.
 */
class TrackingEventPublisherTest {

    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");
    private static final UUID SHIPMENT_ID = UUID.fromString("00c5356b-8b0b-47e5-b88c-1e504dd2bf34");

    private final TrackingEventPublisher publisher = new TrackingEventPublisher(
            null, Clock.fixed(NOW, ZoneOffset.UTC), "carrier-tracking-events");

    @Test
    @DisplayName("sequence numbers start at 1 and increase by one, matching a carrier's scan count")
    void sequenceNumbersAreMonotonicFromOne() {
        List<CarrierTrackingEventMessage> events = publisher.buildEvents(request("SWIFTPOST"));

        assertThat(events).isNotEmpty();
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).sequenceNumber()).isEqualTo(i + 1L);
        }
    }

    @Test
    @DisplayName("event times increase and the journey ends at the current instant")
    void eventTimesTellAPlausibleStory() {
        List<CarrierTrackingEventMessage> events = publisher.buildEvents(request("SWIFTPOST"));

        assertThat(events)
                .extracting(CarrierTrackingEventMessage::eventTime)
                .isSortedAccordingTo(Comparator.naturalOrder());
        assertThat(events.getLast().eventTime()).isEqualTo(NOW);
        // Back-dated across the whole journey rather than bunched into one second.
        assertThat(Duration.between(events.getFirst().eventTime(), events.getLast().eventTime()))
                .isGreaterThan(Duration.ofHours(24));
    }

    @Test
    @DisplayName("every event carries a unique id, the shared correlation id, and schema version 1")
    void identifiersAreCorrect() {
        List<CarrierTrackingEventMessage> events = publisher.buildEvents(request("SWIFTPOST"));

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
        assertThat(publisher.buildEvents(request("SWIFTPOST")))
                .extracting(CarrierTrackingEventMessage::eventType)
                .startsWith("SP_CREATED")
                .endsWith("SP_DELIVERED")
                // If the simulator emitted normalized values the tracking service's normalization
                // layer would never be exercised end to end.
                .doesNotContain("LABEL_CREATED", "DELIVERED");

        assertThat(publisher.buildEvents(request("PACIFICA")))
                .extracting(CarrierTrackingEventMessage::eventType)
                .startsWith("MANIFESTED")
                .endsWith("COMPLETE")
                .doesNotContain("LABEL_CREATED", "DELIVERED");
    }

    private static SimulationRequest request(String carrier) {
        return new SimulationRequest(
                SHIPMENT_ID, "SP-1", carrier, SimulationRequest.Scenario.NORMAL,
                Duration.ZERO, "corr-1");
    }
}
