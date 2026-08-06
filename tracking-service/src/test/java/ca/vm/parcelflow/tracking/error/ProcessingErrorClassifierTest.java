package ca.vm.parcelflow.tracking.error;

import static org.assertj.core.api.Assertions.assertThat;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.carrier.normalization.UnknownCarrierEventTypeException;
import ca.vm.parcelflow.carrier.normalization.UnsupportedCarrierException;
import ca.vm.parcelflow.shipment.ShipmentNotFoundException;
import ca.vm.parcelflow.tracking.CarrierMismatchException;
import ca.vm.parcelflow.tracking.InvalidCarrierEventException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.serializer.DeserializationException;

/**
 * Retry policy, expressed as a classification.
 *
 * <p>This is the test that stops "do not retry all RuntimeExceptions" from decaying back into
 * retrying everything: each category is pinned to a concrete exception, and each exception to a
 * concrete answer about whether re-running it can help.
 */
class ProcessingErrorClassifierTest {

    private final ProcessingErrorClassifier classifier = new ProcessingErrorClassifier();

    @Nested
    @DisplayName("non-retryable")
    class NonRetryable {

        @Test
        @DisplayName("a payload that cannot be deserialized will never deserialize")
        void malformedPayload() {
            var failure = new DeserializationException(
                    "bad json", new byte[0], false, new IllegalStateException("nope"));

            assertCategory(failure, ErrorCategory.MALFORMED_PAYLOAD, false, false);
        }

        @Test
        void validationFailure() {
            assertCategory(new InvalidCarrierEventException("missing correlationId"),
                    ErrorCategory.VALIDATION, false, false);
        }

        @Test
        void unknownEventType() {
            assertCategory(
                    new UnknownCarrierEventTypeException(CarrierCode.SWIFTPOST, "SP_TELEPORTED"),
                    ErrorCategory.UNKNOWN_EVENT_TYPE, false, false);
        }

        @Test
        void carrierMismatch() {
            assertCategory(
                    new CarrierMismatchException(
                            UUID.randomUUID(), CarrierCode.SWIFTPOST, CarrierCode.PACIFICA),
                    ErrorCategory.CARRIER_MISMATCH, false, false);
        }

        @Test
        @DisplayName("an unsupported carrier is not auto-retryable but becomes retryable after a deploy")
        void unsupportedCarrierIsManuallyRetryable() {
            assertCategory(new UnsupportedCarrierException(CarrierCode.NORDEX),
                    ErrorCategory.UNSUPPORTED_CARRIER, false, true);
        }

        @Test
        @DisplayName("an unrecognised failure is not retried automatically")
        void unknownFailureIsNotAutoRetried() {
            // Conservative on purpose: an unclassified failure is as likely to be a bug that fails
            // identically forever as it is to be transient, and an operator looking at the record
            // can decide better than a backoff policy can.
            assertCategory(new IllegalStateException("something new"),
                    ErrorCategory.UNKNOWN, false, true);
        }
    }

    @Nested
    @DisplayName("retryable")
    class Retryable {

        @Test
        @DisplayName("shipment-not-found is retryable: a carrier scan can beat the registration call")
        void shipmentNotFound() {
            assertCategory(new ShipmentNotFoundException(UUID.randomUUID()),
                    ErrorCategory.SHIPMENT_NOT_FOUND, true, true);
        }

        @Test
        void optimisticLockConflict() {
            assertCategory(new OptimisticLockingFailureException("version mismatch"),
                    ErrorCategory.CONCURRENCY_CONFLICT, true, true);
        }

        @Test
        void databaseUnavailable() {
            assertCategory(new DataAccessResourceFailureException("connection refused"),
                    ErrorCategory.INFRASTRUCTURE, true, true);
            assertCategory(new QueryTimeoutException("statement timed out"),
                    ErrorCategory.INFRASTRUCTURE, true, true);
        }
    }

    @Nested
    @DisplayName("unwrapping")
    class Unwrapping {

        @Test
        @DisplayName("a listener wrapper is unwrapped to the failure that describes the problem")
        void unwrapsListenerExecutionFailure() {
            // Classifying the wrapper would put every single failure in UNKNOWN, and the whole
            // retry policy would collapse into "never retry".
            var wrapped = new ListenerExecutionFailedException(
                    "listener threw", new InvalidCarrierEventException("missing eventId"));

            assertThat(classifier.classify(wrapped)).isEqualTo(ErrorCategory.VALIDATION);
        }

        @Test
        @DisplayName("nested wrappers are unwrapped to the deepest recognised cause")
        void unwrapsNestedWrappers() {
            var wrapped = new RuntimeException("outer",
                    new IllegalStateException("middle",
                            new ShipmentNotFoundException(UUID.randomUUID())));

            assertThat(classifier.classify(wrapped)).isEqualTo(ErrorCategory.SHIPMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("a self-referencing cause chain terminates instead of looping forever")
        void toleratesSelfReferentialCause() {
            var looping = new RuntimeException("loop") {
                @Override
                public synchronized Throwable getCause() {
                    return this;
                }
            };

            assertThat(classifier.classify(looping)).isEqualTo(ErrorCategory.UNKNOWN);
        }
    }

    @Test
    @DisplayName("every exception in the Kafka not-retryable list classifies as non-auto-retryable")
    void notRetryableListAgreesWithTheClassifier() {
        // The Kafka error handler builds its skip list from this constant while the failed-event
        // record uses classify(). If the two ever disagreed, a message would be recorded as
        // retryable but never actually retried, or the reverse.
        assertThat(ProcessingErrorClassifier.NON_RETRYABLE_EXCEPTIONS).isNotEmpty();
        assertThat(ProcessingErrorClassifier.NON_RETRYABLE_EXCEPTIONS).allSatisfy(type ->
                assertThat(exampleOf(type))
                        .as("%s", type.getSimpleName())
                        .matches(e -> !classifier.classify(e).isRetryableAutomatically()));
    }

    private static Throwable exampleOf(Class<? extends Exception> type) {
        if (type == DeserializationException.class) {
            return new DeserializationException("bad", new byte[0], false, new RuntimeException());
        }
        if (type == InvalidCarrierEventException.class) {
            return new InvalidCarrierEventException("bad");
        }
        if (type == UnknownCarrierEventTypeException.class) {
            return new UnknownCarrierEventTypeException(CarrierCode.SWIFTPOST, "X");
        }
        if (type == UnsupportedCarrierException.class) {
            return new UnsupportedCarrierException(CarrierCode.NORDEX);
        }
        if (type == CarrierMismatchException.class) {
            return new CarrierMismatchException(
                    UUID.randomUUID(), CarrierCode.SWIFTPOST, CarrierCode.PACIFICA);
        }
        throw new AssertionError(
                "No example for " + type + "; add one so this list stays fully covered");
    }

    private void assertCategory(
            Throwable failure,
            ErrorCategory expected,
            boolean retryableAutomatically,
            boolean retryableManually) {

        ErrorCategory category = classifier.classify(failure);
        assertThat(category).isEqualTo(expected);
        assertThat(category.isRetryableAutomatically()).isEqualTo(retryableAutomatically);
        assertThat(category.isRetryableManually()).isEqualTo(retryableManually);
    }
}
