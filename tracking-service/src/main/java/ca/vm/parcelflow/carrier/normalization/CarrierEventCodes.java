package ca.vm.parcelflow.carrier.normalization;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import java.util.Locale;
import java.util.Map;

/**
 * Shared lookup used by every {@link CarrierEventNormalizer}.
 *
 * <p>Carriers are inconsistent about whitespace and casing across their own integrations, and that
 * inconsistency is not worth a dead letter. Anything beyond trimming and upper-casing — a code the
 * carrier genuinely never told us about — is an error the operator needs to see.
 */
final class CarrierEventCodes {

    private CarrierEventCodes() {
    }

    static ShipmentStatus lookup(
            Map<String, ShipmentStatus> mappings, CarrierCode carrierCode, String carrierEventType) {
        if (carrierEventType == null || carrierEventType.isBlank()) {
            throw new UnknownCarrierEventTypeException(carrierCode, carrierEventType);
        }
        ShipmentStatus status =
                mappings.get(carrierEventType.trim().toUpperCase(Locale.ROOT));
        if (status == null) {
            throw new UnknownCarrierEventTypeException(carrierCode, carrierEventType);
        }
        return status;
    }
}
