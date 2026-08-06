package ca.vm.parcelflow.notification;

import ca.vm.parcelflow.notification.domain.Notification;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    boolean existsByShipmentIdAndSourceEventId(UUID shipmentId, UUID sourceEventId);

    Page<Notification> findByShipmentId(UUID shipmentId, Pageable pageable);
}
