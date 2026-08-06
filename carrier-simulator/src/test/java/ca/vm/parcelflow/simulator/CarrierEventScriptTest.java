package ca.vm.parcelflow.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CarrierEventScriptTest {

    @ParameterizedTest
    @EnumSource(CarrierEventScript.class)
    @DisplayName("every carrier's normal journey starts at label creation and ends at delivery")
    void journeysAreCompleteAndOrdered(CarrierEventScript script) {
        List<CarrierEventScript.Step> steps = script.steps();

        assertThat(steps).hasSizeGreaterThan(3);
        assertThat(steps.getFirst().elapsedSincePrevious()).isZero();
        assertThat(steps).allSatisfy(step -> {
            assertThat(step.carrierEventType()).isNotBlank();
            assertThat(step.location()).isNotBlank();
            assertThat(step.description()).isNotBlank();
            assertThat(step.elapsedSincePrevious()).isGreaterThanOrEqualTo(Duration.ZERO);
        });
    }

    @Test
    @DisplayName("the two carriers publish genuinely different vocabularies")
    void carrierVocabulariesDoNotOverlap() {
        List<String> swiftPost = CarrierEventScript.SWIFTPOST.steps().stream()
                .map(CarrierEventScript.Step::carrierEventType).toList();
        List<String> pacifica = CarrierEventScript.PACIFICA.steps().stream()
                .map(CarrierEventScript.Step::carrierEventType).toList();

        assertThat(swiftPost).doesNotContainAnyElementsOf(pacifica);
    }

    @Test
    @DisplayName("carrier lookup is case-insensitive and rejects unknown carriers with guidance")
    void looksUpByCarrierCode() {
        assertThat(CarrierEventScript.forCarrier("swiftpost"))
                .isEqualTo(CarrierEventScript.SWIFTPOST);
        assertThat(CarrierEventScript.forCarrier(" PACIFICA "))
                .isEqualTo(CarrierEventScript.PACIFICA);

        // NORDEX is a valid CarrierCode in the tracking service but the simulator has no script
        // for it, and neither does the service have a normalizer.
        assertThatThrownBy(() -> CarrierEventScript.forCarrier("NORDEX"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SWIFTPOST");
    }
}
