package ca.vm.parcelflow.tracking;

import ca.vm.parcelflow.shipment.ShipmentService;
import ca.vm.parcelflow.tracking.domain.TrackingEvent;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of tracking history. */
@Service
public class TrackingHistoryService {

    /**
     * Chronological order, tie-broken by the carrier's sequence number.
     *
     * <p>Fixed here rather than accepted from the caller. History is a narrative — the order is
     * part of what the endpoint means, not a display preference — and the composite index is built
     * for exactly this ordering, so allowing arbitrary sorts would let a client request a full sort
     * of a parcel's history.
     */
    private static final Sort CHRONOLOGICAL =
            Sort.by(Sort.Order.asc("eventTime"), Sort.Order.asc("sequenceNumber"));

    private final TrackingEventRepository trackingEventRepository;
    private final ShipmentService shipmentService;

    public TrackingHistoryService(
            TrackingEventRepository trackingEventRepository, ShipmentService shipmentService) {
        this.trackingEventRepository = trackingEventRepository;
        this.shipmentService = shipmentService;
    }

    /**
     * @throws ca.vm.parcelflow.shipment.ShipmentNotFoundException if the shipment does not exist,
     *     so an unknown parcel is a 404 rather than an empty page that looks like a parcel with no
     *     scans yet
     */
    @Transactional(readOnly = true)
    public Page<TrackingEvent> findShipmentHistory(UUID shipmentId, int page, int size) {
        shipmentService.requireShipmentExists(shipmentId);
        return trackingEventRepository.findByShipmentId(
                shipmentId, PageRequest.of(page, size, CHRONOLOGICAL));
    }
}
