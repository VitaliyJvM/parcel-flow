package ca.vm.parcelflow.shared.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.util.Map;

/**
 * OpenAPI description of the {@code application/problem+json} body {@link ApiExceptionHandler}
 * writes. Documentation only: nothing constructs this record.
 *
 * <p>It exists because pointing springdoc at Spring's {@code ProblemDetail} documents that class's
 * Java shape — including a nested {@code properties} object — while Jackson serializes those
 * properties as top-level extension members ({@code carrierCode}, {@code trackingNumber},
 * {@code errors}). A client generated from the class would look for fields that are never sent.
 */
@Schema(
        name = "Problem",
        description = """
                RFC 9457 problem document. Branch on `type`, not on `title` or `detail`. \
                Problem-specific extension members (for example `carrierCode` and \
                `trackingNumber` on shipment-not-found) appear at the top level.""",
        additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
public record ProblemResponse(
        @Schema(example = "https://parcelflow.example/problems/shipment-not-found")
        URI type,

        @Schema(example = "Shipment not found")
        String title,

        @Schema(example = "404")
        int status,

        @Schema(example = "No shipment found for carrier SWIFTPOST and tracking number SP-12345")
        String detail,

        @Schema(example = "/api/shipments/lookup")
        URI instance,

        @Schema(description = "Parameter or field name to message. Present on validation failures.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Map<String, String> errors) {
}
