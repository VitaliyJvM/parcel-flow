package ca.vm.parcelflow.tracking.failure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FailedEventRepository extends JpaRepository<FailedEvent, UUID> {

    Optional<FailedEvent> findByEventId(UUID eventId);

    Page<FailedEvent> findByStatus(FailedEventStatus status, Pageable pageable);

    /**
     * Atomically claims a failed event for a manual retry.
     *
     * <p>A compare-and-set in one statement: the {@code WHERE} clause only matches a row still in
     * {@code FAILED}, so two operators clicking retry at the same moment produce one update of 1
     * row and one of 0. The loser is rejected rather than reprocessing the same event concurrently
     * with the winner.
     *
     * <p>Done as a conditional {@code UPDATE} rather than read-modify-write because the check and
     * the write are then a single atomic statement, with no window between them for the second
     * caller to slip through.
     *
     * @return 1 if this caller claimed the event, 0 if it was already claimed or resolved
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE FailedEvent f
               SET f.status = ca.vm.parcelflow.tracking.failure.FailedEventStatus.RETRYING
             WHERE f.failedEventId = :failedEventId
               AND f.status = ca.vm.parcelflow.tracking.failure.FailedEventStatus.FAILED
            """)
    int claimForRetry(@Param("failedEventId") UUID failedEventId);
}
