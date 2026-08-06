package ca.vm.parcelflow.carrier.normalization;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;

/**
 * Translates one carrier's private event vocabulary into ParcelFlow's normalized vocabulary.
 *
 * <p>One implementation per carrier, each registered as a Spring bean and discovered by
 * {@link CarrierEventNormalizers}. Adding a carrier means adding a class, never editing a switch
 * statement in the consumer.
 */
public interface CarrierEventNormalizer {

    /** The carrier this normalizer speaks for. Must be unique across all normalizer beans. */
    CarrierCode carrierCode();

    /**
     * @param carrierEventType the carrier's own event code, exactly as it arrived on the wire
     * @return the normalized status this event represents
     * @throws UnknownCarrierEventTypeException if this carrier has no mapping for the code
     */
    ShipmentStatus normalize(String carrierEventType);
}
