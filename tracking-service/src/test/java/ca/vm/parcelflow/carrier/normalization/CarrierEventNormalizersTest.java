package ca.vm.parcelflow.carrier.normalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Registry behaviour: routing, gaps in coverage, and misconfiguration. */
class CarrierEventNormalizersTest {

    private final CarrierEventNormalizers normalizers = new CarrierEventNormalizers(
            List.of(new SwiftPostEventNormalizer(), new PacificaEventNormalizer()));

    @Test
    @DisplayName("routes each carrier's code to that carrier's normalizer")
    void routesByCarrier() {
        assertThat(normalizers.normalize(CarrierCode.SWIFTPOST, "SP_OFD"))
                .isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
        assertThat(normalizers.normalize(CarrierCode.PACIFICA, "COURIER_ROUTE"))
                .isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    @DisplayName("a code valid for one carrier is rejected for another")
    void doesNotShareVocabularyBetweenCarriers() {
        assertThatThrownBy(() -> normalizers.normalize(CarrierCode.PACIFICA, "SP_OFD"))
                .isInstanceOf(UnknownCarrierEventTypeException.class);
    }

    @Test
    @DisplayName("a carrier with no normalizer produces UnsupportedCarrierException, not a mapping error")
    void unregisteredCarrierIsDistinctFromUnknownCode() {
        // NORDEX and METROLINK exist in CarrierCode but have no Stage 2 normalizer. The distinction
        // matters: this is a configuration gap to fill, not a malformed message to dead letter.
        assertThatThrownBy(() -> normalizers.normalize(CarrierCode.NORDEX, "ANYTHING"))
                .isInstanceOf(UnsupportedCarrierException.class)
                .hasMessageContaining("NORDEX");

        assertThatThrownBy(() -> normalizers.normalize(CarrierCode.METROLINK, "ANYTHING"))
                .isInstanceOf(UnsupportedCarrierException.class);
    }

    @Test
    void reportsWhichCarriersAreSupported() {
        assertThat(normalizers.supportedCarriers())
                .containsExactlyInAnyOrder(CarrierCode.SWIFTPOST, CarrierCode.PACIFICA);
    }

    @Test
    @DisplayName("two normalizers for one carrier fail at construction, not at bean-ordering roulette")
    void rejectsDuplicateRegistration() {
        List<CarrierEventNormalizer> duplicates =
                List.of(new SwiftPostEventNormalizer(), new SwiftPostEventNormalizer());

        assertThatThrownBy(() -> new CarrierEventNormalizers(duplicates))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SWIFTPOST");
    }

    @Test
    @DisplayName("an empty registry is legal but rejects everything")
    void emptyRegistryRejectsEverything() {
        CarrierEventNormalizers empty = new CarrierEventNormalizers(List.of());

        assertThat(empty.supportedCarriers()).isEmpty();
        assertThatThrownBy(() -> empty.normalize(CarrierCode.SWIFTPOST, "SP_OFD"))
                .isInstanceOf(UnsupportedCarrierException.class);
    }
}
