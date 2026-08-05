package ca.vm.parcelflow.shipment.api;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.shipment.RegisterShipmentCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Request body for {@code POST /api/shipments}. */
@Schema(description = "Registers a parcel for tracking")
public record RegisterShipmentRequest(
        @Schema(example = "retailer-42")
        @NotBlank
        @Size(max = 64)
        String retailerId,

        @Schema(description = "Opaque retailer-side customer reference. Never logged.",
                example = "cust-9f13")
        @NotBlank
        @Size(max = 64)
        String customerId,

        @Schema(example = "SP100000000042")
        @NotBlank
        @Size(max = 128)
        String trackingNumber,

        @NotNull
        CarrierCode carrierCode,

        @Schema(example = "2026-08-12")
        LocalDate estimatedDeliveryDate) {

    public RegisterShipmentCommand toCommand() {
        return new RegisterShipmentCommand(
                retailerId, customerId, trackingNumber, carrierCode, estimatedDeliveryDate);
    }
}
