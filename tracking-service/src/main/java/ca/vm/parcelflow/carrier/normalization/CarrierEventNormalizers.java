package ca.vm.parcelflow.carrier.normalization;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Registry of the per-carrier normalizers.
 *
 * <p>Spring injects every {@link CarrierEventNormalizer} bean, so supporting a new carrier is one
 * new class with a {@code @Component} annotation — no registration list to update and no switch
 * statement anywhere near the Kafka listener.
 *
 * <p>Two carriers being registered for the same {@link CarrierCode} is a wiring mistake that would
 * otherwise resolve arbitrarily depending on bean ordering, so it fails at startup rather than in
 * production.
 */
@Component
public class CarrierEventNormalizers {

    private static final Logger log = LoggerFactory.getLogger(CarrierEventNormalizers.class);

    private final Map<CarrierCode, CarrierEventNormalizer> byCarrier;

    public CarrierEventNormalizers(List<CarrierEventNormalizer> normalizers) {
        Map<CarrierCode, CarrierEventNormalizer> registry = new EnumMap<>(CarrierCode.class);
        for (CarrierEventNormalizer normalizer : normalizers) {
            CarrierEventNormalizer existing = registry.put(normalizer.carrierCode(), normalizer);
            if (existing != null) {
                throw new IllegalStateException(
                        "Two normalizers registered for carrier %s: %s and %s".formatted(
                                normalizer.carrierCode(),
                                existing.getClass().getName(),
                                normalizer.getClass().getName()));
            }
        }
        this.byCarrier = Map.copyOf(registry);
        log.info("Carrier event normalizers registered for {}", supportedCarriers());
    }

    /**
     * @throws UnsupportedCarrierException if no normalizer is registered for the carrier
     * @throws UnknownCarrierEventTypeException if the carrier does not recognise the event code
     */
    public ShipmentStatus normalize(CarrierCode carrierCode, String carrierEventType) {
        CarrierEventNormalizer normalizer = byCarrier.get(carrierCode);
        if (normalizer == null) {
            throw new UnsupportedCarrierException(carrierCode);
        }
        return normalizer.normalize(carrierEventType);
    }

    public Set<CarrierCode> supportedCarriers() {
        return byCarrier.keySet();
    }
}
