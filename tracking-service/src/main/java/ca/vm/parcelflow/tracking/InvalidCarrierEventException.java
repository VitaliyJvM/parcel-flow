package ca.vm.parcelflow.tracking;

/**
 * Thrown when a carrier event is structurally unusable: a required field is missing, a value
 * violates its constraint, or the schema version is one this service does not understand.
 *
 * <p>Permanently invalid. Retrying it will produce the same result, which is what makes it a dead
 * letter candidate in Stage 3 rather than something to back off and re-attempt.
 */
public class InvalidCarrierEventException extends RuntimeException {

    public InvalidCarrierEventException(String message) {
        super(message);
    }
}
