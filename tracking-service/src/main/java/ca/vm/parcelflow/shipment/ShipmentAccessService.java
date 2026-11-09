package ca.vm.parcelflow.shipment;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.security.CurrentUser;
import ca.vm.parcelflow.security.RetailerAccessDeniedException;
import ca.vm.parcelflow.shipment.api.ShipmentResponse;
import ca.vm.parcelflow.shipment.domain.Shipment;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retailer isolation for every user-facing shipment operation.
 *
 * <p>The HTTP API reaches shipments only through here (or through a service that calls
 * {@link #requireAccessible}); {@link ShipmentService} stays the unscoped domain service that the
 * Kafka consumer and other background processing use, because they act for no user. The decision
 * itself is {@link CurrentUser#canAccessRetailer}; this class decides what a refusal looks like.
 *
 * <h2>Refusal semantics</h2>
 * <ul>
 *   <li><b>A specific shipment</b> — by id, by carrier and tracking number, its history, its
 *       notifications — that belongs to another retailer is reported exactly like one that does not
 *       exist: the same exception, so the same 404 status, problem type and body. A retailer cannot
 *       tell "not yours" from "no such shipment", so cannot probe for other retailers' parcels.</li>
 *   <li><b>A retailer scope</b> the caller named themselves — listing, or registering under another
 *       retailer's id — is a 403. The caller already knows the retailer id they sent; refusing it
 *       discloses nothing about any shipment.</li>
 *   <li><b>A duplicate registration</b> is still a 409: the (carrier, tracking number) pair is
 *       globally unique, and that constraint is not relaxed per retailer. What the 409 adds is only
 *       whether the caller may open the existing shipment, never whose it is or its id.</li>
 * </ul>
 *
 * <p>No class-level transaction: {@link #register} must look the duplicate's owner up in a fresh
 * transaction, after the failed insert's transaction has rolled back.
 */
@Service
public class ShipmentAccessService {

    private final ShipmentService shipmentService;
    private final ShipmentRepository shipmentRepository;

    public ShipmentAccessService(ShipmentService shipmentService, ShipmentRepository shipmentRepository) {
        this.shipmentService = shipmentService;
        this.shipmentRepository = shipmentRepository;
    }

    /**
     * @throws RetailerAccessDeniedException if a retailer registers under another retailer's id
     * @throws DuplicateTrackingNumberException if the pair is already registered, carrying whether
     *     the caller may open the existing shipment
     */
    public Shipment register(CurrentUser viewer, RegisterShipmentCommand command) {
        requireRetailerScope(viewer, command.retailerId());
        try {
            return shipmentService.registerShipment(command);
        } catch (DuplicateTrackingNumberException e) {
            boolean accessible = shipmentService.findRetailerId(command.carrierCode(), command.trackingNumber())
                    .map(viewer::canAccessRetailer)
                    .orElse(false);
            throw e.withExistingShipmentAccessible(accessible);
        }
    }

    /**
     * The cached tracking view, checked after the cache. A cache hit for another retailer's shipment
     * is refused exactly like a miss on a nonexistent one.
     *
     * @throws ShipmentNotFoundException if the shipment does not exist or is not the caller's
     */
    public ShipmentResponse getTracking(CurrentUser viewer, UUID shipmentId) {
        ShipmentResponse shipment = shipmentService.getShipmentTracking(shipmentId);
        if (!viewer.canAccessRetailer(shipment.retailerId())) {
            throw new ShipmentNotFoundException(shipmentId);
        }
        return shipment;
    }

    /** @throws TrackingNumberNotFoundException if no shipment matches or it is not the caller's */
    public ShipmentResponse lookup(CurrentUser viewer, CarrierCode carrierCode, String trackingNumber) {
        ShipmentResponse shipment = shipmentService.lookupShipment(carrierCode, trackingNumber);
        if (!viewer.canAccessRetailer(shipment.retailerId())) {
            throw new TrackingNumberNotFoundException(carrierCode, trackingNumber);
        }
        return shipment;
    }

    /** @throws RetailerAccessDeniedException if a retailer lists another retailer */
    public Page<Shipment> listRetailerShipments(
            CurrentUser viewer, String retailerId, ShipmentStatus status, Pageable pageable) {
        requireRetailerScope(viewer, retailerId);
        return shipmentService.findRetailerShipments(retailerId, status, pageable);
    }

    /**
     * Guards reads of data hanging off a shipment (tracking history, notifications). Joins the
     * caller's read-only transaction when there is one.
     *
     * @throws ShipmentNotFoundException if the shipment does not exist or is not the caller's
     */
    @Transactional(readOnly = true)
    public void requireAccessible(CurrentUser viewer, UUID shipmentId) {
        boolean accessible = shipmentRepository.findRetailerIdByShipmentId(shipmentId)
                .map(viewer::canAccessRetailer)
                .orElse(false);
        if (!accessible) {
            throw new ShipmentNotFoundException(shipmentId);
        }
    }

    private static void requireRetailerScope(CurrentUser viewer, String retailerId) {
        if (!viewer.canAccessRetailer(retailerId)) {
            throw new RetailerAccessDeniedException();
        }
    }
}
