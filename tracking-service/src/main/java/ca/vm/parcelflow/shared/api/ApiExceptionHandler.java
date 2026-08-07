package ca.vm.parcelflow.shared.api;

import ca.vm.parcelflow.shipment.DuplicateTrackingNumberException;
import ca.vm.parcelflow.shipment.ShipmentNotFoundException;
import ca.vm.parcelflow.tracking.failure.FailedEventExceptions;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates exceptions into RFC 9457 {@code application/problem+json} responses.
 *
 * <p>Every response carries a stable {@code type} URI so clients can branch on the error kind
 * without string-matching messages. Validation failures additionally carry a flat
 * {@code errors} map of field name to message.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String TYPE_PREFIX = "https://parcelflow.example/problems/";

    private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER =
            new DefaultParameterNameDiscoverer();

    @ExceptionHandler(ShipmentNotFoundException.class)
    public ProblemDetail handleShipmentNotFound(ShipmentNotFoundException e) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "Shipment not found", e.getMessage(),
                "shipment-not-found");
        problem.setProperty("shipmentId", e.getShipmentId().toString());
        return problem;
    }

    @ExceptionHandler(DuplicateTrackingNumberException.class)
    public ProblemDetail handleDuplicateTrackingNumber(DuplicateTrackingNumberException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Duplicate tracking number",
                e.getMessage(), "duplicate-tracking-number");
        problem.setProperty("carrierCode", e.getCarrierCode().name());
        problem.setProperty("trackingNumber", e.getTrackingNumber());
        return problem;
    }

    @ExceptionHandler(FailedEventExceptions.NotFound.class)
    public ProblemDetail handleFailedEventNotFound(FailedEventExceptions.NotFound e) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "Failed event not found",
                e.getMessage(), "failed-event-not-found");
        problem.setProperty("failedEventId", e.getFailedEventId().toString());
        return problem;
    }

    /**
     * The event's failure can never be fixed by reprocessing the same bytes. 409 rather than 400:
     * the request is well-formed, the resource is simply not in a state that allows the action.
     */
    @ExceptionHandler(FailedEventExceptions.NotRetryable.class)
    public ProblemDetail handleFailedEventNotRetryable(FailedEventExceptions.NotRetryable e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Failed event is not retryable",
                e.getMessage(), "failed-event-not-retryable");
        problem.setProperty("errorCategory", e.getErrorCategory().name());
        return problem;
    }

    /** Another retry already claimed the record, or it is already resolved. */
    @ExceptionHandler(FailedEventExceptions.RetryNotAvailable.class)
    public ProblemDetail handleRetryNotAvailable(FailedEventExceptions.RetryNotAvailable e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Retry not available",
                e.getMessage(), "failed-event-retry-not-available");
        problem.setProperty("status", e.getStatus().name());
        return problem;
    }

    /** Bean validation failure on an {@code @Valid @RequestBody} argument. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleInvalidRequestBody(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .forEach(fieldError -> errors.putIfAbsent(
                        fieldError.getField(),
                        fieldError.getDefaultMessage() == null
                                ? "is invalid"
                                : fieldError.getDefaultMessage()));

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed",
                "The request body failed validation", "validation-failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    /** Bean validation failure on controller method parameters (the {@code @Validated} path). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleInvalidParameters(HandlerMethodValidationException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getParameterValidationResults().forEach(result ->
                result.getResolvableErrors().stream()
                        .findFirst()
                        .ifPresent(error -> errors.putIfAbsent(
                                parameterName(result.getMethodParameter()),
                                error.getDefaultMessage())));

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed",
                "One or more request parameters are invalid", "validation-failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    /**
     * Resolves the name a client actually sent.
     *
     * <p>{@code MethodParameter.getParameterName()} returns {@code null} here because the
     * {@code MethodParameter} instances carried by the exception have no parameter-name discoverer
     * attached. The binding annotation is the more accurate source anyway: it is what the client
     * sees when {@code @RequestParam("page_number") int page} renames a parameter.
     */
    private static String parameterName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null && !requestParam.name().isEmpty()) {
            return requestParam.name();
        }
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        if (pathVariable != null && !pathVariable.name().isEmpty()) {
            return pathVariable.name();
        }
        parameter.initParameterNameDiscovery(PARAMETER_NAME_DISCOVERER);
        String discovered = parameter.getParameterName();
        return discovered == null ? "parameter" : discovered;
    }

    /**
     * Bean validation raised outside the MVC parameter path — most realistically from Hibernate
     * validating an entity before flush.
     *
     * <p>Property paths from method validation are qualified with the method name
     * ({@code listRetailerShipments.size}); only the trailing segment is meaningful to a client.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : e.getConstraintViolations()) {
            String path = violation.getPropertyPath().toString();
            String field = path.substring(path.lastIndexOf('.') + 1);
            errors.putIfAbsent(field.isEmpty() ? path : field, violation.getMessage());
        }

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed",
                "One or more request parameters are invalid", "validation-failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    /**
     * Unparseable body, or a value that cannot be bound — an unknown {@code carrierCode} enum
     * constant is the common case. The exception message is not echoed back: it contains Jackson
     * internals and class names.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException e) {
        log.debug("Rejected unreadable request body", e);
        return problem(HttpStatus.BAD_REQUEST, "Malformed request",
                "The request body could not be parsed", "malformed-request");
    }

    /** A path variable or query parameter of the wrong type, e.g. a non-UUID shipment id. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Malformed request",
                "Parameter '%s' has an invalid value".formatted(e.getName()), "malformed-request");
        problem.setProperty("errors", Map.of(e.getName(), "is not a valid value"));
        return problem;
    }

    /**
     * A request for a path this service does not serve.
     *
     * <p>Without this, the catch-all below turns every unmatched path into a 500 — which is wrong
     * for the client, who is told the server broke when in fact the URL does not exist, and worse
     * for operations: a bot walking {@code /actuator/env}, {@code /.env} and {@code /wp-login.php}
     * produces a stream of ERROR lines with stack traces, and the error rate that alerts fire on
     * starts tracking internet background noise instead of this service's health.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(NoResourceFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found",
                "No endpoint is mapped to this path", "not-found");
    }

    /**
     * Last resort. Logs at error level with the stack trace and returns a body with no internal
     * detail, so a stack trace or SQL fragment never leaves the process.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unhandled exception while serving request", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                "The request could not be processed", "internal-error");
    }

    private static ProblemDetail problem(
            HttpStatus status, String title, String detail, String type) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(TYPE_PREFIX + type));
        return problem;
    }
}
