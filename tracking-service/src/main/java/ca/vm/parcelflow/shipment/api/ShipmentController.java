package ca.vm.parcelflow.shipment.api;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.shared.api.ProblemResponse;
import ca.vm.parcelflow.shipment.ShipmentService;
import ca.vm.parcelflow.shipment.domain.Shipment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * No {@code @Validated} on this class, for the reason given on {@code RetailerShipmentController}:
 * Spring MVC validates constrained controller parameters natively and reports them as a
 * {@code HandlerMethodValidationException} with clean per-parameter names.
 */
@RestController
@RequestMapping("/api/shipments")
@Tag(name = "Shipments", description = "Register parcels and read their current tracking state")
public class ShipmentController {

    private static final String PROBLEM_JSON = "application/problem+json";

    /** Mirrors {@code RegisterShipmentRequest.trackingNumber} and the column width. */
    private static final int MAX_TRACKING_NUMBER_LENGTH = 128;

    private final ShipmentService shipmentService;

    public ShipmentController(ShipmentService shipmentService) {
        this.shipmentService = shipmentService;
    }

    @PostMapping
    @Operation(summary = "Register a shipment for tracking")
    @ApiResponse(responseCode = "201", description = "Shipment registered")
    @ApiResponse(responseCode = "400", description = "Validation failed", content = {})
    @ApiResponse(responseCode = "409", description = "Tracking number already registered for this carrier",
            content = {})
    public ResponseEntity<ShipmentResponse> registerShipment(
            @Valid @RequestBody RegisterShipmentRequest request) {
        Shipment shipment = shipmentService.registerShipment(request.toCommand());
        return ResponseEntity.created(URI.create("/api/shipments/" + shipment.getShipmentId()))
                .body(ShipmentResponse.from(shipment));
    }

    @GetMapping("/{shipmentId}")
    @Operation(
            summary = "Get the current status of a shipment",
            description = """
                    Served from a Redis cache that is evicted whenever an event advances the \
                    shipment, and expires on a TTL as a backstop. PostgreSQL remains the source of \
                    truth: if Redis is unavailable the response is identical, just slower.""")
    @ApiResponse(responseCode = "200", description = "Current shipment state")
    @ApiResponse(responseCode = "404", description = "No such shipment", content = {})
    public ShipmentResponse getShipment(@PathVariable UUID shipmentId) {
        return shipmentService.getShipmentTracking(shipmentId);
    }

    /**
     * A literal path segment under the same prefix as {@code /{shipmentId}}. Spring MVC ranks a
     * pattern without variables above one with them, so {@code /lookup} is never offered to
     * {@link #getShipment} as a malformed UUID.
     *
     * <p>Query parameters rather than path segments: a tracking number is carrier-issued free text
     * and may contain characters ({@code /}, {@code %}) that are awkward or rejected in a path.
     */
    @GetMapping("/lookup")
    @Operation(
            summary = "Find a shipment by carrier and tracking number",
            description = """
                    Resolves the carrier-issued tracking number to the shipment registered under \
                    it. A tracking number is unique per carrier, not globally, so the carrier is \
                    required. Matching is exact: case-sensitive and not trimmed, the same \
                    comparison the duplicate-registration check uses. Read from PostgreSQL, not \
                    the cache.""")
    @ApiResponse(responseCode = "200", description = "The matching shipment")
    @ApiResponse(responseCode = "400",
            description = "A parameter is missing, blank, too long, or not a known carrier code",
            content = @Content(mediaType = PROBLEM_JSON,
                    schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "404",
            description = "No shipment is registered for this carrier and tracking number",
            content = @Content(mediaType = PROBLEM_JSON,
                    schema = @Schema(implementation = ProblemResponse.class)))
    public ShipmentResponse lookupShipment(
            @Parameter(description = "Carrier that issued the tracking number", example = "SWIFTPOST")
            @RequestParam CarrierCode carrierCode,
            @Parameter(description = "Carrier-issued tracking number, matched exactly",
                    example = "SP100000000042")
            @RequestParam @NotBlank @Size(max = MAX_TRACKING_NUMBER_LENGTH) String trackingNumber) {
        return shipmentService.lookupShipment(carrierCode, trackingNumber);
    }
}
