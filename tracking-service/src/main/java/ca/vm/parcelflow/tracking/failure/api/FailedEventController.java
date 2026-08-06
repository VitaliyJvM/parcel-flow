package ca.vm.parcelflow.tracking.failure.api;

import ca.vm.parcelflow.shared.api.PageResponse;
import ca.vm.parcelflow.tracking.failure.FailedEventService;
import ca.vm.parcelflow.tracking.failure.FailedEventStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative endpoints for triaging events that could not be processed.
 *
 * <p><b>These endpoints are unauthenticated in this portfolio build.</b> They expose operational
 * detail about failures across every retailer and can trigger reprocessing, so in any real
 * deployment they belong behind an operator role and on an interface that is not reachable from the
 * public internet. Authentication is out of the MVP scope, not out of the design: the {@code
 * /api/admin} prefix exists so the whole subtree can be secured with one rule.
 */
@RestController
@RequestMapping("/api/admin/failed-events")
@Tag(name = "Admin: failed events",
        description = "Operational triage of events that could not be processed. "
                + "Unauthenticated in this portfolio build — secure before any real deployment.")
public class FailedEventController {

    private static final int MAX_PAGE_SIZE = 100;

    private final FailedEventService failedEventService;

    public FailedEventController(FailedEventService failedEventService) {
        this.failedEventService = failedEventService;
    }

    @GetMapping
    @Operation(
            summary = "List failed events, most recent failure first",
            description = """
                    Responses carry the exception type and message but never a stack trace or the \
                    original payload.""")
    public PageResponse<FailedEventResponse> listFailedEvents(
            @RequestParam(required = false) FailedEventStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        return PageResponse.from(
                failedEventService.findFailedEvents(status, page, size), FailedEventResponse::from);
    }

    @GetMapping("/{failedEventId}")
    @Operation(summary = "Get one failed event")
    @ApiResponse(responseCode = "404", description = "No such failed event", content = {})
    public FailedEventResponse getFailedEvent(@PathVariable UUID failedEventId) {
        return FailedEventResponse.from(failedEventService.getFailedEvent(failedEventId));
    }

    @PostMapping("/{failedEventId}/retry")
    @Operation(
            summary = "Reprocess a failed event",
            description = """
                    Runs the event through the normal processing pipeline in-process and reports \
                    the real outcome, rather than republishing to Kafka and answering "accepted".

                    Rejected with 409 when the failure category can never succeed on retry \
                    (a malformed payload, a missing field), and when another retry already holds \
                    the record. Reprocessing is idempotent: an event that did eventually get \
                    stored comes back as a duplicate, not an error.""")
    @ApiResponse(responseCode = "200", description = "Retry attempted; body reports the outcome")
    @ApiResponse(responseCode = "404", description = "No such failed event", content = {})
    @ApiResponse(responseCode = "409",
            description = "Not retryable, or a retry is already in progress", content = {})
    public FailedEventRetryResponse retryFailedEvent(@PathVariable UUID failedEventId) {
        return FailedEventRetryResponse.from(failedEventService.retry(failedEventId));
    }
}
