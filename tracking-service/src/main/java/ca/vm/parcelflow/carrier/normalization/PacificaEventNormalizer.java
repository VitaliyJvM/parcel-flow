package ca.vm.parcelflow.carrier.normalization;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Pacifica sends unprefixed words drawn from freight terminology.
 *
 * <p>Pacifica is a fictional carrier. Two of its codes are worth noting because they show why
 * normalization is not a string transform:
 *
 * <ul>
 *   <li>{@code DELIVERY_FAILED} maps to {@link ShipmentStatus#DELIVERY_ATTEMPTED} — the parcel is
 *       still in the network and will be re-attempted, so "failed" overstates it.
 *   <li>{@code COMPLETE} maps to {@link ShipmentStatus#DELIVERED} — nothing in the string suggests
 *       delivery.
 * </ul>
 */
@Component
public class PacificaEventNormalizer implements CarrierEventNormalizer {

    private static final Map<String, ShipmentStatus> MAPPINGS = Map.of(
            "MANIFESTED", ShipmentStatus.LABEL_CREATED,
            "COLLECTED", ShipmentStatus.PICKED_UP,
            "MOVING", ShipmentStatus.IN_TRANSIT,
            "AT_TERMINAL", ShipmentStatus.ARRIVED_AT_FACILITY,
            "COURIER_ROUTE", ShipmentStatus.OUT_FOR_DELIVERY,
            "EXCEPTION_DELAY", ShipmentStatus.DELAYED,
            "DELIVERY_FAILED", ShipmentStatus.DELIVERY_ATTEMPTED,
            "COMPLETE", ShipmentStatus.DELIVERED);

    @Override
    public CarrierCode carrierCode() {
        return CarrierCode.PACIFICA;
    }

    @Override
    public ShipmentStatus normalize(String carrierEventType) {
        return CarrierEventCodes.lookup(MAPPINGS, carrierCode(), carrierEventType);
    }
}
