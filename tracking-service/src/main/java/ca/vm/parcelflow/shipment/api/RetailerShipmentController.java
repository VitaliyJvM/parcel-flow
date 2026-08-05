package ca.vm.parcelflow.shipment.api;

import ca.vm.parcelflow.shipment.ShipmentService;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import ca.vm.parcelflow.shared.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * No {@code @Validated} on this class: Spring MVC applies method validation to controller
 * parameters natively when constraint annotations are present. Adding {@code @Validated} would
 * instead route validation through an AOP proxy, which raises {@code ConstraintViolationException}
 * with method-prefixed property paths ({@code listRetailerShipments.size}) and loses the parameter
 * metadata needed to report a clean per-parameter error.
 */
@RestController
@Tag(name = "Retailers", description = "Retailer-scoped shipment queries")
public class RetailerShipmentController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ShipmentService shipmentService;

    public RetailerShipmentController(ShipmentService shipmentService) {
        this.shipmentService = shipmentService;
    }

    /**
     * Pagination is expressed as explicit, validated query parameters rather than an injected
     * {@code Pageable}. That keeps the page-size ceiling and the sort order part of the visible API
     * contract (and part of the generated OpenAPI document) instead of relying on framework
     * defaults an unbounded {@code size=100000} request could slip past.
     */
    @GetMapping("/api/retailers/{retailerId}/shipments")
    @Operation(summary = "List a retailer's shipments, newest first")
    public PageResponse<ShipmentResponse> listRetailerShipments(
            @PathVariable String retailerId,
            @Parameter(description = "Filter by current status") @RequestParam(required = false)
                    ShipmentStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        PageRequest pageRequest =
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.from(
                shipmentService.findRetailerShipments(retailerId, status, pageRequest),
                ShipmentResponse::from);
    }
}
