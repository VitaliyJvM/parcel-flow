package ca.vm.parcelflow.shipment;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.shipment.domain.Shipment;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentRepository extends JpaRepository<Shipment, UUID> {

    Optional<Shipment> findByCarrierCodeAndTrackingNumber(CarrierCode carrierCode, String trackingNumber);

    Page<Shipment> findByRetailerId(String retailerId, Pageable pageable);

    Page<Shipment> findByRetailerIdAndCurrentStatus(
            String retailerId, ShipmentStatus currentStatus, Pageable pageable);

    /**
     * Backs the {@code parcelflow.shipments.active} gauge.
     *
     * <p>"Active" is defined as not delivered rather than by listing the in-flight statuses, so a
     * new status added to the enum is counted as active by default. Getting that wrong in the safe
     * direction means a slightly high gauge; the other default would silently stop counting real
     * parcels.
     */
    long countByCurrentStatusNot(ShipmentStatus currentStatus);
}
