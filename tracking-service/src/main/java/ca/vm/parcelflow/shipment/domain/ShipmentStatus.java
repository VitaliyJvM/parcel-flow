package ca.vm.parcelflow.shipment.domain;

/**
 * The normalized shipment lifecycle status.
 *
 * <p>Deliberately <em>not</em> a ranked progression. A parcel's real journey is not linear:
 * {@code DELAYED} and {@code DELIVERY_ATTEMPTED} are exception states that can occur at several
 * points, and {@code ARRIVED_AT_FACILITY} can repeat many times. Assigning each status an integer
 * rank and using it to decide which update wins would encode a lie about how carrier networks
 * behave.
 *
 * <p>Ordering authority therefore lives in the event itself — {@code sequenceNumber} first,
 * {@code eventTime} as fallback — see {@link Shipment#recordEvent}. The only ordering rule attached
 * to the status is terminality.
 */
public enum ShipmentStatus {

    LABEL_CREATED,
    PICKED_UP,
    IN_TRANSIT,
    ARRIVED_AT_FACILITY,
    OUT_FOR_DELIVERY,
    DELAYED,
    DELIVERY_ATTEMPTED,
    DELIVERED;

    /**
     * A terminal status is final: no later event may move the shipment out of it.
     *
     * <p>ParcelFlow has no post-delivery states (returns, re-shipments), so {@code DELIVERED} is
     * sticky. This makes a delivered parcel immune to a late {@code IN_TRANSIT} scan arriving with
     * a higher sequence number, which does happen when carriers backfill scans.
     */
    public boolean isTerminal() {
        return this == DELIVERED;
    }
}
