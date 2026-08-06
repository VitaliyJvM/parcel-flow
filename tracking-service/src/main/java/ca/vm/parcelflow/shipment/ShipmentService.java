package ca.vm.parcelflow.shipment;

import ca.vm.parcelflow.infrastructure.config.CacheConfiguration;
import ca.vm.parcelflow.shipment.api.ShipmentResponse;
import ca.vm.parcelflow.shipment.domain.Shipment;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import java.time.Clock;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application service for the shipment lifecycle. All transaction boundaries start here. */
@Service
public class ShipmentService {

    private final ShipmentRepository shipmentRepository;
    private final Clock clock;

    public ShipmentService(ShipmentRepository shipmentRepository, Clock clock) {
        this.shipmentRepository = shipmentRepository;
        this.clock = clock;
    }

    @Transactional
    public Shipment registerShipment(RegisterShipmentCommand command) {
        Shipment shipment = Shipment.register(
                UUID.randomUUID(),
                command.retailerId(),
                command.customerId(),
                command.trackingNumber(),
                command.carrierCode(),
                command.estimatedDeliveryDate(),
                clock.instant());
        try {
            // saveAndFlush, not save: the INSERT must hit the database inside this try block,
            // otherwise the unique-constraint violation surfaces at commit time — outside our
            // reach — and the caller gets a 500 instead of a 409.
            return shipmentRepository.saveAndFlush(shipment);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateTrackingNumberException(
                    command.carrierCode(), command.trackingNumber(), e);
        }
    }

    @Transactional(readOnly = true)
    public Shipment getShipment(UUID shipmentId) {
        return shipmentRepository
                .findById(shipmentId)
                .orElseThrow(() -> new ShipmentNotFoundException(shipmentId));
    }

    /**
     * The cached read behind {@code GET /api/shipments/{shipmentId}}.
     *
     * <p>Returns the response DTO rather than the entity, and the DTO is what gets cached. Caching
     * a JPA entity would mean serializing a managed object with a version field and handing
     * detached copies to callers; a record has none of those problems and is what the endpoint
     * needs anyway.
     *
     * <p>{@link ShipmentNotFoundException} is not cached — Spring's cache abstraction never stores
     * a value for a method that threw — so a parcel registered a second after someone looked for it
     * is visible immediately rather than 404-ing until a TTL expires.
     *
     * <p>Kept as a separate method from {@link #getShipment} because the cache is a property of
     * this specific read path. Internal callers that need the entity should not be served a
     * possibly-stale projection.
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheConfiguration.SHIPMENT_TRACKING_CACHE, key = "#shipmentId")
    public ShipmentResponse getShipmentTracking(UUID shipmentId) {
        return ShipmentResponse.from(getShipment(shipmentId));
    }

    /**
     * Asserts a shipment exists without loading it.
     *
     * <p>For callers in other modules that need the 404 semantics but not the entity — returning
     * the {@code Shipment} instead would leak the aggregate across a module boundary and pull a row
     * nobody reads.
     *
     * @throws ShipmentNotFoundException if there is no such shipment
     */
    @Transactional(readOnly = true)
    public void requireShipmentExists(UUID shipmentId) {
        if (!shipmentRepository.existsById(shipmentId)) {
            throw new ShipmentNotFoundException(shipmentId);
        }
    }

    @Transactional(readOnly = true)
    public Page<Shipment> findRetailerShipments(
            String retailerId, ShipmentStatus status, Pageable pageable) {
        return status == null
                ? shipmentRepository.findByRetailerId(retailerId, pageable)
                : shipmentRepository.findByRetailerIdAndCurrentStatus(retailerId, status, pageable);
    }
}
