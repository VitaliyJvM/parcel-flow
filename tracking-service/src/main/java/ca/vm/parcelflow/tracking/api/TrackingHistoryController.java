package ca.vm.parcelflow.tracking.api;

import ca.vm.parcelflow.shared.api.PageResponse;
import ca.vm.parcelflow.tracking.TrackingHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tracking history for a parcel.
 *
 * <p>Follows the same pagination convention as the Stage 1 endpoints: explicit validated
 * parameters, a ceiling that is part of the contract, and the shared {@code PageResponse} envelope.
 */
@RestController
@Tag(name = "Tracking history", description = "Carrier scans recorded for a parcel")
public class TrackingHistoryController {

    private static final int MAX_PAGE_SIZE = 200;

    private final TrackingHistoryService trackingHistoryService;

    public TrackingHistoryController(TrackingHistoryService trackingHistoryService) {
        this.trackingHistoryService = trackingHistoryService;
    }

    @GetMapping("/api/shipments/{shipmentId}/events")
    @Operation(
            summary = "Get a parcel's tracking history",
            description = """
                    Events are ordered by event time ascending, then by the carrier's sequence \
                    number. Superseded events — those that arrived too late to change the current \
                    status — are included: they are real observations and belong in the history.""")
    @ApiResponse(responseCode = "200", description = "A page of tracking events")
    @ApiResponse(responseCode = "404", description = "No such shipment", content = {})
    public PageResponse<TrackingEventResponse> getTrackingHistory(
            @PathVariable UUID shipmentId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        return PageResponse.from(
                trackingHistoryService.findShipmentHistory(shipmentId, page, size),
                TrackingEventResponse::from);
    }
}
