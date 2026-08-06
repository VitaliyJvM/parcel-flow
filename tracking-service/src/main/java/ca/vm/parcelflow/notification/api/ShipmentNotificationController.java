package ca.vm.parcelflow.notification.api;

import ca.vm.parcelflow.notification.NotificationService;
import ca.vm.parcelflow.shared.api.PageResponse;
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

@RestController
@Tag(name = "Notifications", description = "Notification records generated for delivery milestones")
public class ShipmentNotificationController {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationService notificationService;

    public ShipmentNotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/api/shipments/{shipmentId}/notifications")
    @Operation(
            summary = "List the notification records generated for a parcel",
            description = """
                    Records only — ParcelFlow does not send email or SMS. Ordered oldest first by \
                    creation time, tie-broken by notification id so paging is deterministic when \
                    several notifications share a timestamp.""")
    @ApiResponse(responseCode = "200", description = "A page of notification records")
    @ApiResponse(responseCode = "404", description = "No such shipment", content = {})
    public PageResponse<NotificationResponse> getNotifications(
            @PathVariable UUID shipmentId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        return PageResponse.from(
                notificationService.findShipmentNotifications(shipmentId, page, size),
                NotificationResponse::from);
    }
}
