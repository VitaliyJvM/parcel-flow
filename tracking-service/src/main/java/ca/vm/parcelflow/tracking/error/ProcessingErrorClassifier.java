package ca.vm.parcelflow.tracking.error;

import ca.vm.parcelflow.carrier.normalization.UnknownCarrierEventTypeException;
import ca.vm.parcelflow.carrier.normalization.UnsupportedCarrierException;
import ca.vm.parcelflow.shipment.ShipmentNotFoundException;
import ca.vm.parcelflow.tracking.CarrierMismatchException;
import ca.vm.parcelflow.tracking.InvalidCarrierEventException;
import java.util.List;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.stereotype.Component;

/**
 * Maps a failure to an {@link ErrorCategory}.
 *
 * <p>The single source of truth for retry policy. The Kafka error handler, the failed-event record,
 * and the manual retry endpoint all consult this, so the three cannot drift into disagreeing about
 * whether a given failure is worth another attempt.
 */
@Component
public class ProcessingErrorClassifier {

    /**
     * Exception types the Kafka error handler must not re-deliver.
     *
     * <p>Exposed as a constant so {@code KafkaErrorHandlingConfiguration} builds its
     * not-retryable list from the same declaration this class classifies against, rather than
     * maintaining a parallel list that silently drifts.
     */
    public static final List<Class<? extends Exception>> NON_RETRYABLE_EXCEPTIONS = List.of(
            DeserializationException.class,
            InvalidCarrierEventException.class,
            UnknownCarrierEventTypeException.class,
            UnsupportedCarrierException.class,
            CarrierMismatchException.class);

    public ErrorCategory classify(Throwable throwable) {
        Throwable cause = rootProcessingCause(throwable);

        return switch (cause) {
            case null -> ErrorCategory.UNKNOWN;
            case DeserializationException ignored -> ErrorCategory.MALFORMED_PAYLOAD;
            case InvalidCarrierEventException ignored -> ErrorCategory.VALIDATION;
            case UnknownCarrierEventTypeException ignored -> ErrorCategory.UNKNOWN_EVENT_TYPE;
            case UnsupportedCarrierException ignored -> ErrorCategory.UNSUPPORTED_CARRIER;
            case CarrierMismatchException ignored -> ErrorCategory.CARRIER_MISMATCH;
            case ShipmentNotFoundException ignored -> ErrorCategory.SHIPMENT_NOT_FOUND;
            case OptimisticLockingFailureException ignored -> ErrorCategory.CONCURRENCY_CONFLICT;
            case PessimisticLockingFailureException ignored -> ErrorCategory.CONCURRENCY_CONFLICT;
            case ConcurrencyFailureException ignored -> ErrorCategory.CONCURRENCY_CONFLICT;
            case DataAccessResourceFailureException ignored -> ErrorCategory.INFRASTRUCTURE;
            case QueryTimeoutException ignored -> ErrorCategory.INFRASTRUCTURE;
            case TransientDataAccessException ignored -> ErrorCategory.INFRASTRUCTURE;
            default -> ErrorCategory.UNKNOWN;
        };
    }

    /**
     * Unwraps framework wrappers to reach the exception that actually describes the problem.
     *
     * <p>Spring Kafka wraps listener failures in {@code ListenerExecutionFailedException}, and a
     * deserialization failure arrives wrapped again. Classifying the wrapper would put every
     * failure in {@code UNKNOWN}.
     *
     * <p>Walks to the deepest cause that this classifier recognises, rather than blindly to the
     * root: the root of a JDBC failure is often a driver-specific exception that says less than the
     * Spring translation wrapping it.
     */
    private Throwable rootProcessingCause(Throwable throwable) {
        Throwable recognised = null;
        Throwable current = throwable;
        int guard = 0;

        // The guard bounds a self-referential cause chain, which malformed exception hierarchies
        // occasionally produce.
        while (current != null && guard++ < 20) {
            if (isRecognised(current)) {
                recognised = current;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return recognised != null ? recognised : throwable;
    }

    private boolean isRecognised(Throwable throwable) {
        return throwable instanceof DeserializationException
                || throwable instanceof InvalidCarrierEventException
                || throwable instanceof UnknownCarrierEventTypeException
                || throwable instanceof UnsupportedCarrierException
                || throwable instanceof CarrierMismatchException
                || throwable instanceof ShipmentNotFoundException
                || throwable instanceof ConcurrencyFailureException
                || throwable instanceof TransientDataAccessException
                || throwable instanceof DataAccessResourceFailureException;
    }
}
