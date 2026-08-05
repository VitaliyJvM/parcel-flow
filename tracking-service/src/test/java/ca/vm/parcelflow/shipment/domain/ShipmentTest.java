package ca.vm.parcelflow.shipment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ca.vm.parcelflow.carrier.CarrierCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the ordering rules that decide whether an event advances the shipment.
 *
 * <p>No Spring context and no database: this is the pure decision logic that every distributed
 * scenario in the project ultimately relies on.
 */
class ShipmentTest {

    private static final Instant T0 = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

    private static Shipment newShipment() {
        return Shipment.register(
                UUID.randomUUID(),
                "retailer-1",
                "cust-1",
                "SP1",
                CarrierCode.SWIFTPOST,
                LocalDate.parse("2026-08-05"),
                T0);
    }

    @Test
    @DisplayName("a new shipment starts at LABEL_CREATED with nothing applied")
    void startsAtLabelCreated() {
        Shipment shipment = newShipment();

        assertThat(shipment.getCurrentStatus()).isEqualTo(ShipmentStatus.LABEL_CREATED);
        assertThat(shipment.getLastEventTime()).isNull();
        assertThat(shipment.getLastSequenceNumber()).isNull();
        assertThat(shipment.getCreatedAt()).isEqualTo(T0);
        assertThat(shipment.getUpdatedAt()).isEqualTo(T0);
    }

    @Nested
    @DisplayName("in-order events")
    class InOrder {

        @Test
        void firstEventAlwaysApplies() {
            Shipment shipment = newShipment();

            boolean advanced = shipment.recordEvent(
                    ShipmentStatus.PICKED_UP, T0.plusSeconds(60), 1, NOW);

            assertThat(advanced).isTrue();
            assertThat(shipment.getCurrentStatus()).isEqualTo(ShipmentStatus.PICKED_UP);
            assertThat(shipment.getLastEventTime()).isEqualTo(T0.plusSeconds(60));
            assertThat(shipment.getLastSequenceNumber()).isEqualTo(1L);
            assertThat(shipment.getUpdatedAt()).isEqualTo(NOW);
        }

        @Test
        void higherSequenceNumberAdvancesTheShipment() {
            Shipment shipment = newShipment();
            shipment.recordEvent(ShipmentStatus.PICKED_UP, T0.plusSeconds(60), 1, NOW);

            boolean advanced = shipment.recordEvent(
                    ShipmentStatus.IN_TRANSIT, T0.plusSeconds(120), 2, NOW);

            assertThat(advanced).isTrue();
            assertThat(shipment.getCurrentStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        }

        @Test
        @DisplayName("ARRIVED_AT_FACILITY may repeat: a later scan at a new facility still advances")
        void repeatedStatusStillAdvances() {
            Shipment shipment = newShipment();
            shipment.recordEvent(ShipmentStatus.ARRIVED_AT_FACILITY, T0.plusSeconds(60), 1, NOW);

            boolean advanced = shipment.recordEvent(
                    ShipmentStatus.ARRIVED_AT_FACILITY, T0.plusSeconds(600), 2, NOW);

            assertThat(advanced).isTrue();
            assertThat(shipment.getLastSequenceNumber()).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("out-of-order events")
    class OutOfOrder {

        @Test
        @DisplayName("an older IN_TRANSIT arriving after OUT_FOR_DELIVERY does not rewind the status")
        void olderEventDoesNotRewindStatus() {
            Shipment shipment = newShipment();
            shipment.recordEvent(ShipmentStatus.OUT_FOR_DELIVERY, T0.plusSeconds(300), 5, NOW);

            boolean advanced = shipment.recordEvent(
                    ShipmentStatus.IN_TRANSIT, T0.plusSeconds(120), 3, NOW);

            assertThat(advanced).isFalse();
            assertThat(shipment.getCurrentStatus()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
            assertThat(shipment.getLastEventTime()).isEqualTo(T0.plusSeconds(300));
            assertThat(shipment.getLastSequenceNumber()).isEqualTo(5L);
        }

        @Test
        @DisplayName("the same sequence number replayed does not advance the shipment")
        void sameSequenceNumberDoesNotAdvance() {
            Shipment shipment = newShipment();
            shipment.recordEvent(ShipmentStatus.IN_TRANSIT, T0.plusSeconds(120), 3, NOW);

            boolean advanced = shipment.recordEvent(
                    ShipmentStatus.DELAYED, T0.plusSeconds(120), 3, NOW);

            assertThat(advanced).isFalse();
            assertThat(shipment.getCurrentStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        }

        @Test
        @DisplayName("sequence number wins over event time when a carrier clock is skewed")
        void sequenceNumberOutranksEventTime() {
            Shipment shipment = newShipment();
            shipment.recordEvent(ShipmentStatus.IN_TRANSIT, T0.plusSeconds(600), 4, NOW);

            // Sequence says newer, timestamp says older — the scanner clock was behind.
            boolean advanced = shipment.recordEvent(
                    ShipmentStatus.OUT_FOR_DELIVERY, T0.plusSeconds(300), 5, NOW);

            assertThat(advanced).isTrue();
            assertThat(shipment.getCurrentStatus()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
        }

        @Test
        @DisplayName("event time breaks ties when two events share a sequence number")
        void eventTimeBreaksTiesOnEqualSequenceNumbers() {
            Shipment shipment = newShipment();
            shipment.recordEvent(ShipmentStatus.IN_TRANSIT, T0.plusSeconds(120), 7, NOW);

            boolean advanced = shipment.recordEvent(
                    ShipmentStatus.ARRIVED_AT_FACILITY, T0.plusSeconds(180), 7, NOW);

            assertThat(advanced).isTrue();
            assertThat(shipment.getCurrentStatus()).isEqualTo(ShipmentStatus.ARRIVED_AT_FACILITY);
        }

        @Test
        @DisplayName("a rejected event leaves updatedAt untouched")
        void rejectedEventDoesNotTouchUpdatedAt() {
            Shipment shipment = newShipment();
            shipment.recordEvent(ShipmentStatus.OUT_FOR_DELIVERY, T0.plusSeconds(300), 5, NOW);
            Instant afterApply = shipment.getUpdatedAt();

            shipment.recordEvent(
                    ShipmentStatus.IN_TRANSIT, T0.plusSeconds(120), 3, NOW.plusSeconds(3600));

            assertThat(shipment.getUpdatedAt()).isEqualTo(afterApply);
        }
    }

    @Nested
    @DisplayName("terminal state")
    class Terminal {

        @Test
        void deliveredIsTheOnlyTerminalStatus() {
            assertThat(ShipmentStatus.DELIVERED.isTerminal()).isTrue();
            for (ShipmentStatus status : ShipmentStatus.values()) {
                if (status != ShipmentStatus.DELIVERED) {
                    assertThat(status.isTerminal()).as("%s", status).isFalse();
                }
            }
        }

        @Test
        @DisplayName("a backfilled scan with a higher sequence number cannot un-deliver a parcel")
        void deliveredShipmentIgnoresLaterEvents() {
            Shipment shipment = newShipment();
            shipment.recordEvent(ShipmentStatus.DELIVERED, T0.plusSeconds(600), 8, NOW);

            boolean advanced = shipment.recordEvent(
                    ShipmentStatus.IN_TRANSIT, T0.plusSeconds(900), 99, NOW);

            assertThat(advanced).isFalse();
            assertThat(shipment.getCurrentStatus()).isEqualTo(ShipmentStatus.DELIVERED);
            assertThat(shipment.getLastSequenceNumber()).isEqualTo(8L);
        }

        @Test
        @DisplayName("a DELIVERED event that arrives early still freezes the shipment")
        void earlyDeliveredFreezesShipment() {
            Shipment shipment = newShipment();
            shipment.recordEvent(ShipmentStatus.IN_TRANSIT, T0.plusSeconds(120), 3, NOW);

            assertThat(shipment.recordEvent(ShipmentStatus.DELIVERED, T0.plusSeconds(600), 8, NOW))
                    .isTrue();
            assertThat(shipment.recordEvent(
                            ShipmentStatus.OUT_FOR_DELIVERY, T0.plusSeconds(300), 5, NOW))
                    .isFalse();
            assertThat(shipment.getCurrentStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        }
    }

    @Test
    @DisplayName("toString never exposes the customer reference")
    void toStringOmitsCustomerId() {
        Shipment shipment = newShipment();

        assertThat(shipment.toString()).doesNotContain("cust-1");
    }
}
