package ca.vm.parcelflow.shipment;

import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import java.util.UUID;

/**
 * Published when an event actually moved a shipment's current status.
 *
 * <p>Deliberately <em>not</em> published for superseded events: they change nothing a reader could
 * observe, so invalidating the cache for them would evict a still-correct entry and buy nothing.
 *
 * <p>Consumed by {@code ShipmentCacheInvalidator} on {@code AFTER_COMMIT}. An in-process Spring
 * event, not a Kafka message — the only listener lives in the same transaction manager, and putting
 * a broker in the middle of a cache eviction would add a failure mode without adding a capability.
 */
public record ShipmentStatusAdvanced(UUID shipmentId, ShipmentStatus currentStatus) {
}
