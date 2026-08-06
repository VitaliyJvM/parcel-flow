package ca.vm.parcelflow.tracking;

import ca.vm.parcelflow.tracking.domain.TrackingEvent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrackingEventRepository extends JpaRepository<TrackingEvent, Long> {

    Optional<TrackingEvent> findByEventId(UUID eventId);

    /**
     * Ordering is imposed by the {@link Pageable} the service builds, not by a method name, so the
     * documented ordering cannot be changed by a caller passing its own sort.
     */
    Page<TrackingEvent> findByShipmentId(UUID shipmentId, Pageable pageable);
}
