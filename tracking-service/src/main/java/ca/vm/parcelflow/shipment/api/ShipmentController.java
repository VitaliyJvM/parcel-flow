package ca.vm.parcelflow.shipment.api;

import ca.vm.parcelflow.shipment.ShipmentService;
import ca.vm.parcelflow.shipment.domain.Shipment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shipments")
@Tag(name = "Shipments", description = "Register parcels and read their current tracking state")
public class ShipmentController {

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
    @Operation(summary = "Get the current status of a shipment")
    @ApiResponse(responseCode = "200", description = "Current shipment state")
    @ApiResponse(responseCode = "404", description = "No such shipment", content = {})
    public ShipmentResponse getShipment(@PathVariable UUID shipmentId) {
        return ShipmentResponse.from(shipmentService.getShipment(shipmentId));
    }
}
