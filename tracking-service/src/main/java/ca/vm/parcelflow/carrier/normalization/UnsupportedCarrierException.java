package ca.vm.parcelflow.carrier.normalization;

import ca.vm.parcelflow.carrier.CarrierCode;

/**
 * Thrown when an event arrives for a carrier that has no registered normalizer.
 *
 * <p>{@code CarrierCode} defines four carriers but only two have normalizers in Stage 2, so this is
 * reachable in normal operation — it is a configuration gap, not a malformed message.
 */
public class UnsupportedCarrierException extends RuntimeException {

    private final CarrierCode carrierCode;

    public UnsupportedCarrierException(CarrierCode carrierCode) {
        super("No event normalizer is registered for carrier %s".formatted(carrierCode));
        this.carrierCode = carrierCode;
    }

    public CarrierCode getCarrierCode() {
        return carrierCode;
    }
}
