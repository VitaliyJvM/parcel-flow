package ca.vm.parcelflow.tracking;

import ca.vm.parcelflow.carrier.normalization.CarrierEventNormalizers;
import ca.vm.parcelflow.notification.NotificationService;
import ca.vm.parcelflow.shipment.ShipmentNotFoundException;
import ca.vm.parcelflow.shipment.ShipmentRepository;
import ca.vm.parcelflow.shipment.ShipmentStatusAdvanced;
import ca.vm.parcelflow.shipment.domain.Shipment;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import ca.vm.parcelflow.tracking.domain.EventProcessingStatus;
import ca.vm.parcelflow.tracking.domain.TrackingEvent;
import ca.vm.parcelflow.tracking.messaging.CarrierTrackingEventMessage;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional unit of work for one carrier event.
 *
 * <p>Separate bean from {@link TrackingEventProcessor} for a concrete reason, not layering taste:
 * the orchestrator has to catch {@code DataIntegrityViolationException} and
 * {@code OptimisticLockingFailureException} and act on them, and both of those leave the
 * transaction they came from marked rollback-only. A Spring {@code @Transactional} method also
 * cannot start a fresh transaction by calling itself — self-invocation bypasses the proxy — so the
 * retry has to cross a bean boundary to get a new one.
 *
 * <p>Everything inside {@link #record} is one transaction: the duplicate check, the history insert,
 * the shipment update, and the notification. Either the parcel's history, its status and its
 * customer notification all move together, or none of them do.
 */
@Service
public class TrackingEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(TrackingEventRecorder.class);

    private final ShipmentRepository shipmentRepository;
    private final TrackingEventRepository trackingEventRepository;
    private final CarrierEventNormalizers normalizers;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;
    private final Validator validator;
    private final Clock clock;

    public TrackingEventRecorder(
            ShipmentRepository shipmentRepository,
            TrackingEventRepository trackingEventRepository,
            CarrierEventNormalizers normalizers,
            NotificationService notificationService,
            ApplicationEventPublisher eventPublisher,
            Validator validator,
            Clock clock) {
        this.shipmentRepository = shipmentRepository;
        this.trackingEventRepository = trackingEventRepository;
        this.normalizers = normalizers;
        this.notificationService = notificationService;
        this.eventPublisher = eventPublisher;
        this.validator = validator;
        this.clock = clock;
    }

    /**
     * Processes one event in a new transaction.
     *
     * <p>{@code REQUIRES_NEW} so that each retry attempt from the orchestrator genuinely starts
     * over. Joining an existing transaction would mean a second attempt runs inside the persistence
     * context the first attempt already poisoned.
     *
     * @throws InvalidCarrierEventException if the message is structurally unusable
     * @throws ShipmentNotFoundException if the event references an unknown shipment
     * @throws CarrierMismatchException if the event's carrier disagrees with the shipment's
     * @throws org.springframework.dao.DataIntegrityViolationException if the event id already
     *     exists — a duplicate that slipped past the pre-check under a race
     * @throws org.springframework.dao.OptimisticLockingFailureException if another thread updated
     *     the shipment first
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TrackingEventProcessingResult record(CarrierTrackingEventMessage message, int attempt) {
        validate(message);

        // Fast path for the overwhelmingly common duplicate: Kafka redelivered a record whose
        // transaction already committed. Returning here avoids provoking a constraint violation,
        // which would cost a rolled-back transaction and a stack unwind for an expected condition.
        Optional<TrackingEvent> alreadyProcessed =
                trackingEventRepository.findByEventId(message.eventId());
        if (alreadyProcessed.isPresent()) {
            return duplicateResult(alreadyProcessed.get(), attempt);
        }

        Shipment shipment = shipmentRepository
                .findById(message.shipmentId())
                .orElseThrow(() -> new ShipmentNotFoundException(message.shipmentId()));

        if (shipment.getCarrierCode() != message.carrierCode()) {
            throw new CarrierMismatchException(
                    shipment.getShipmentId(), shipment.getCarrierCode(), message.carrierCode());
        }

        ShipmentStatus normalized =
                normalizers.normalize(message.carrierCode(), message.eventType());

        Instant now = clock.instant();

        // The ordering decision belongs to the domain model. Because it is a pure function of
        // (current state, incoming event), replaying it after a rollback produces the same answer —
        // which is exactly what makes the retry below safe.
        boolean advanced = shipment.recordEvent(
                normalized, message.eventTime(), message.sequenceNumber(), now);

        EventProcessingStatus processingStatus =
                advanced ? EventProcessingStatus.APPLIED : EventProcessingStatus.SUPERSEDED;

        // saveAndFlush, not save: the INSERT must hit the database inside this method so that a
        // duplicate event id surfaces as an exception the orchestrator can classify. Deferred to
        // commit, it would escape as an UnexpectedRollbackException from somewhere unhelpful.
        trackingEventRepository.saveAndFlush(TrackingEvent.builder()
                .eventId(message.eventId())
                .shipmentId(shipment.getShipmentId())
                .trackingNumber(message.trackingNumber())
                .carrierCode(message.carrierCode())
                .carrierEventType(message.eventType())
                .normalizedEventType(normalized)
                .eventTime(message.eventTime())
                .receivedAt(now)
                .sequenceNumber(message.sequenceNumber())
                .location(message.location())
                .description(message.description())
                .correlationId(message.correlationId())
                .processingStatus(processingStatus)
                .build());

        if (advanced) {
            // Only applied events notify. A superseded event describes a milestone the parcel
            // passed earlier; telling a customer their delivered parcel is in transit is worse
            // than saying nothing.
            notificationService.recordMilestone(
                    shipment.getShipmentId(), message.eventId(), normalized, now);

            // Consumed after commit. Publishing here rather than in the orchestrator keeps the
            // eviction tied to the transaction that actually changed the row.
            eventPublisher.publishEvent(
                    new ShipmentStatusAdvanced(shipment.getShipmentId(), normalized));
        }

        // The shipment is managed: its update flushes on commit under @Version. No explicit save,
        // and therefore no second write path to keep in sync.

        log.debug("Recorded event {} for shipment {}: {} -> {} ({})",
                message.eventId(), shipment.getShipmentId(), message.eventType(), normalized,
                processingStatus);

        return new TrackingEventProcessingResult(
                message.eventId(),
                shipment.getShipmentId(),
                normalized,
                shipment.getCurrentStatus(),
                processingStatus,
                false,
                attempt);
    }

    /**
     * Builds the result for an event already in history.
     *
     * <p>Reports the status the shipment has <em>now</em>, not the status it had when the event was
     * first applied, because that is what a caller asking "where is this parcel" needs.
     */
    private TrackingEventProcessingResult duplicateResult(TrackingEvent stored, int attempt) {
        ShipmentStatus currentStatus = shipmentRepository
                .findById(stored.getShipmentId())
                .map(Shipment::getCurrentStatus)
                .orElse(null);

        return new TrackingEventProcessingResult(
                stored.getEventId(),
                stored.getShipmentId(),
                stored.getNormalizedEventType(),
                currentStatus,
                stored.getProcessingStatus(),
                true,
                attempt);
    }

    /**
     * Validation is explicit rather than {@code @Valid} on the listener parameter, so a violation
     * arrives as {@link InvalidCarrierEventException} — a type the error classifier can recognise
     * as permanently invalid — instead of a framework wrapper it would have to unwrap and guess at.
     */
    private void validate(CarrierTrackingEventMessage message) {
        if (message == null) {
            throw new InvalidCarrierEventException("Event payload is missing");
        }
        if (message.schemaVersion() == null
                || message.schemaVersion() != CarrierTrackingEventMessage.SUPPORTED_SCHEMA_VERSION) {
            throw new InvalidCarrierEventException(
                    "Unsupported schema version %s; this service understands version %d"
                            .formatted(message.schemaVersion(),
                                    CarrierTrackingEventMessage.SUPPORTED_SCHEMA_VERSION));
        }

        Set<ConstraintViolation<CarrierTrackingEventMessage>> violations = validator.validate(message);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(violation -> "%s %s".formatted(
                            violation.getPropertyPath(), violation.getMessage()))
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.joining(", "));
            throw new InvalidCarrierEventException("Event failed validation: " + detail);
        }
    }
}
