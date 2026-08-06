package ca.vm.parcelflow.carrier.normalization;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * SwiftPost sends terse prefixed scan codes.
 *
 * <p>SwiftPost is a fictional carrier. Its vocabulary is invented to be structurally different from
 * {@link PacificaEventNormalizer}'s, which is the point of the normalization layer.
 */
@Component
public class SwiftPostEventNormalizer implements CarrierEventNormalizer {

    private static final Map<String, ShipmentStatus> MAPPINGS = Map.of(
            "SP_CREATED", ShipmentStatus.LABEL_CREATED,
            "SP_PICKUP", ShipmentStatus.PICKED_UP,
            "SP_TRANSIT", ShipmentStatus.IN_TRANSIT,
            "SP_DEPOT", ShipmentStatus.ARRIVED_AT_FACILITY,
            "SP_OFD", ShipmentStatus.OUT_FOR_DELIVERY,
            "SP_DELAY", ShipmentStatus.DELAYED,
            "SP_ATTEMPT", ShipmentStatus.DELIVERY_ATTEMPTED,
            "SP_DELIVERED", ShipmentStatus.DELIVERED);

    @Override
    public CarrierCode carrierCode() {
        return CarrierCode.SWIFTPOST;
    }

    @Override
    public ShipmentStatus normalize(String carrierEventType) {
        return CarrierEventCodes.lookup(MAPPINGS, carrierCode(), carrierEventType);
    }
}
