package ca.vm.parcelflow.carrier.normalization;

import ca.vm.parcelflow.carrier.CarrierCode;

/**
 * Thrown when a carrier sends an event code its normalizer does not recognise.
 *
 * <p>Distinct from {@link UnsupportedCarrierException}: the carrier is known and supported, but the
 * code is not in its mapping — typically a vocabulary the carrier added without telling anyone. The
 * carrier code and the offending event type are both carried so Stage 3 can put them in the dead
 * letter record.
 */
public class UnknownCarrierEventTypeException extends RuntimeException {

    private final CarrierCode carrierCode;
    private final String carrierEventType;

    public UnknownCarrierEventTypeException(CarrierCode carrierCode, String carrierEventType) {
        super("Carrier %s sent unrecognised event type '%s'".formatted(carrierCode, carrierEventType));
        this.carrierCode = carrierCode;
        this.carrierEventType = carrierEventType;
    }

    public CarrierCode getCarrierCode() {
        return carrierCode;
    }

    public String getCarrierEventType() {
        return carrierEventType;
    }
}
