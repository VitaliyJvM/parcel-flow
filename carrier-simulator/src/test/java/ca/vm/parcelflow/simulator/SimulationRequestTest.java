package ca.vm.parcelflow.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class SimulationRequestTest {

    private static final String SHIPMENT_ID = "00c5356b-8b0b-47e5-b88c-1e504dd2bf34";

    @Test
    @DisplayName("a full command line is parsed into a request")
    void parsesAFullCommandLine() {
        SimulationRequest request = parse(
                "--shipment-id=" + SHIPMENT_ID,
                "--tracking-number=SP100000000042",
                "--carrier=SWIFTPOST",
                "--scenario=NORMAL",
                "--delay-ms=250",
                "--correlation-id=corr-1");

        assertThat(request.shipmentId()).isEqualTo(UUID.fromString(SHIPMENT_ID));
        assertThat(request.trackingNumber()).isEqualTo("SP100000000042");
        assertThat(request.carrierCode()).isEqualTo("SWIFTPOST");
        assertThat(request.scenario()).isEqualTo(SimulationRequest.Scenario.NORMAL);
        assertThat(request.delayBetweenEvents()).isEqualTo(Duration.ofMillis(250));
        assertThat(request.correlationId()).isEqualTo("corr-1");
    }

    @Test
    @DisplayName("optional arguments default, and a correlation id is generated when absent")
    void appliesDefaults() {
        SimulationRequest request = parse(
                "--shipment-id=" + SHIPMENT_ID,
                "--tracking-number=PC-1",
                "--carrier=pacifica");

        assertThat(request.carrierCode()).isEqualTo("PACIFICA");
        assertThat(request.scenario()).isEqualTo(SimulationRequest.Scenario.NORMAL);
        assertThat(request.delayBetweenEvents()).isEqualTo(Duration.ofMillis(500));
        assertThat(request.correlationId()).isNotBlank();
    }

    @Test
    @DisplayName("each missing required argument is named in the error")
    void reportsMissingRequiredArguments() {
        assertThatThrownBy(() -> parse("--tracking-number=SP1", "--carrier=SWIFTPOST"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--shipment-id");

        assertThatThrownBy(() -> parse("--shipment-id=" + SHIPMENT_ID, "--carrier=SWIFTPOST"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--tracking-number");

        assertThatThrownBy(() -> parse("--shipment-id=" + SHIPMENT_ID, "--tracking-number=SP1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--carrier");
    }

    @Test
    @DisplayName("an unknown carrier fails during parsing, before anything is published")
    void rejectsUnknownCarrierUpFront() {
        assertThatThrownBy(() -> parse(
                "--shipment-id=" + SHIPMENT_ID, "--tracking-number=SP1", "--carrier=NOT_A_CARRIER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no event script");
    }

    @Test
    @DisplayName("malformed values are rejected with the offending input")
    void rejectsMalformedValues() {
        assertThatThrownBy(() -> parse(
                "--shipment-id=not-a-uuid", "--tracking-number=SP1", "--carrier=SWIFTPOST"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a UUID");

        assertThatThrownBy(() -> parse("--shipment-id=" + SHIPMENT_ID, "--tracking-number=SP1",
                "--carrier=SWIFTPOST", "--delay-ms=soon"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--delay-ms");

        assertThatThrownBy(() -> parse("--shipment-id=" + SHIPMENT_ID, "--tracking-number=SP1",
                "--carrier=SWIFTPOST", "--delay-ms=-5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");

        assertThatThrownBy(() -> parse("--shipment-id=" + SHIPMENT_ID, "--tracking-number=SP1",
                "--carrier=SWIFTPOST", "--scenario=CHAOS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown --scenario");
    }

    private static SimulationRequest parse(String... args) {
        return SimulationRequest.parse(new DefaultApplicationArguments(args));
    }
}
