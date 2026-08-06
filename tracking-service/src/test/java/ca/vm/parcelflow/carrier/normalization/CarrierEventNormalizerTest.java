package ca.vm.parcelflow.carrier.normalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Per-carrier vocabulary mapping. Pure functions, no Spring. */
class CarrierEventNormalizerTest {

    @Nested
    @DisplayName("SwiftPost")
    class SwiftPost {

        private final SwiftPostEventNormalizer normalizer = new SwiftPostEventNormalizer();

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "SP_CREATED,   LABEL_CREATED",
                "SP_PICKUP,    PICKED_UP",
                "SP_TRANSIT,   IN_TRANSIT",
                "SP_DEPOT,     ARRIVED_AT_FACILITY",
                "SP_OFD,       OUT_FOR_DELIVERY",
                "SP_DELAY,     DELAYED",
                "SP_ATTEMPT,   DELIVERY_ATTEMPTED",
                "SP_DELIVERED, DELIVERED"
        })
        void mapsEveryCode(String carrierEventType, ShipmentStatus expected) {
            assertThat(normalizer.normalize(carrierEventType)).isEqualTo(expected);
        }

        @Test
        void declaresItsCarrier() {
            assertThat(normalizer.carrierCode()).isEqualTo(CarrierCode.SWIFTPOST);
        }

        @Test
        @DisplayName("every normalized status is reachable from some SwiftPost code")
        void coversTheWholeNormalizedVocabulary() {
            List<ShipmentStatus> produced = Arrays.stream(
                            new String[] {"SP_CREATED", "SP_PICKUP", "SP_TRANSIT", "SP_DEPOT",
                                    "SP_OFD", "SP_DELAY", "SP_ATTEMPT", "SP_DELIVERED"})
                    .map(normalizer::normalize)
                    .toList();

            assertThat(produced).containsExactlyInAnyOrder(ShipmentStatus.values());
        }

        @Test
        @DisplayName("casing and stray whitespace are tolerated")
        void toleratesCasingAndWhitespace() {
            assertThat(normalizer.normalize("  sp_ofd  "))
                    .isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
        }

        @Test
        @DisplayName("a Pacifica code is not silently accepted by SwiftPost")
        void rejectsAnotherCarriersVocabulary() {
            assertThatThrownBy(() -> normalizer.normalize("COURIER_ROUTE"))
                    .isInstanceOf(UnknownCarrierEventTypeException.class)
                    .hasMessageContaining("SWIFTPOST")
                    .hasMessageContaining("COURIER_ROUTE");
        }
    }

    @Nested
    @DisplayName("Pacifica")
    class Pacifica {

        private final PacificaEventNormalizer normalizer = new PacificaEventNormalizer();

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "MANIFESTED,      LABEL_CREATED",
                "COLLECTED,       PICKED_UP",
                "MOVING,          IN_TRANSIT",
                "AT_TERMINAL,     ARRIVED_AT_FACILITY",
                "COURIER_ROUTE,   OUT_FOR_DELIVERY",
                "EXCEPTION_DELAY, DELAYED",
                "DELIVERY_FAILED, DELIVERY_ATTEMPTED",
                "COMPLETE,        DELIVERED"
        })
        void mapsEveryCode(String carrierEventType, ShipmentStatus expected) {
            assertThat(normalizer.normalize(carrierEventType)).isEqualTo(expected);
        }

        @Test
        void declaresItsCarrier() {
            assertThat(normalizer.carrierCode()).isEqualTo(CarrierCode.PACIFICA);
        }

        @Test
        @DisplayName("DELIVERY_FAILED is an attempt, not a terminal failure")
        void deliveryFailedIsAnAttempt() {
            ShipmentStatus status = normalizer.normalize("DELIVERY_FAILED");

            assertThat(status).isEqualTo(ShipmentStatus.DELIVERY_ATTEMPTED);
            assertThat(status.isTerminal()).isFalse();
        }

        @Test
        @DisplayName("COMPLETE means delivered, which no string transform would produce")
        void completeMeansDelivered() {
            assertThat(normalizer.normalize("COMPLETE")).isEqualTo(ShipmentStatus.DELIVERED);
        }
    }

    @Nested
    @DisplayName("unrecognised input")
    class UnrecognisedInput {

        private final SwiftPostEventNormalizer normalizer = new SwiftPostEventNormalizer();

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "SP_UNKNOWN", "DELIVERED", "sp_ofd_2"})
        void isRejectedWithTheOffendingCode(String carrierEventType) {
            assertThatThrownBy(() -> normalizer.normalize(carrierEventType))
                    .isInstanceOf(UnknownCarrierEventTypeException.class);
        }

        @Test
        void nullIsRejectedRatherThanThrowingNullPointer() {
            assertThatThrownBy(() -> normalizer.normalize(null))
                    .isInstanceOf(UnknownCarrierEventTypeException.class);
        }

        @Test
        @DisplayName("the exception carries carrier and code for the Stage 3 dead letter record")
        void exceptionCarriesDiagnostics() {
            UnknownCarrierEventTypeException thrown = null;
            try {
                normalizer.normalize("SP_TELEPORTED");
            } catch (UnknownCarrierEventTypeException e) {
                thrown = e;
            }

            assertThat(thrown).isNotNull();
            assertThat(thrown.getCarrierCode()).isEqualTo(CarrierCode.SWIFTPOST);
            assertThat(thrown.getCarrierEventType()).isEqualTo("SP_TELEPORTED");
        }
    }
}
