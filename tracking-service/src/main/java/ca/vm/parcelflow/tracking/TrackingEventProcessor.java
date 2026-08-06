package ca.vm.parcelflow.tracking;

import ca.vm.parcelflow.carrier.normalization.CarrierEventNormalizers;
import ca.vm.parcelflow.shipment.ShipmentNotFoundException;
import ca.vm.parcelflow.shipment.ShipmentRepository;
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
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns one carrier event into a history row and, when the event is newer than what has already
 * been applied, a shipment status change.
 *
 * <p>This is where all the business logic lives. The Kafka listener above it does transport
 * concerns only, which is what allows the same processing path to be driven directly from a test,
 * from a replay tool, or from the Stage 3 admin retry endpoint without going through a broker.
 *
 * <p><strong>Transaction boundary.</strong> The whole method is one transaction. The history insert
 * and the shipment update either both land or neither does — there is no window in which a parcel
 * shows a status that no stored event justifies. Since both writes target the same database, this
 * needs no distributed coordination, which is the main reason ingestion was not split into its own
 * service.
 */
@Service
public class TrackingEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(TrackingEventProcessor.class);

    private final ShipmentRepository shipmentRepository;
    private final TrackingEventRepository trackingEventRepository;
    private final CarrierEventNormalizers normalizers;
    private final Validator validator;
    private final Clock clock;

    public TrackingEventProcessor(
            ShipmentRepository shipmentRepository,
            TrackingEventRepository trackingEventRepository,
            CarrierEventNormalizers normalizers,
            Validator validator,
            Clock clock) {
        this.shipmentRepository = shipmentRepository;
        this.trackingEventRepository = trackingEventRepository;
        this.normalizers = normalizers;
        this.validator = validator;
        this.clock = clock;
    }

    /**
     * @throws InvalidCarrierEventException if the message is structurally unusable
     * @throws ShipmentNotFoundException if the event references an unknown shipment
     * @throws CarrierMismatchException if the event's carrier disagrees with the shipment's
     * @throws ca.vm.parcelflow.carrier.normalization.UnsupportedCarrierException if no normalizer
     *     is registered for the carrier
     * @throws ca.vm.parcelflow.carrier.normalization.UnknownCarrierEventTypeException if the
     *     carrier's event code has no mapping
     */
    @Transactional
    public TrackingEventProcessingResult process(CarrierTrackingEventMessage message) {
        validate(message);

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

        // The ordering decision is the domain model's, not this service's. Stage 1 already proved
        // it in isolation; here it simply decides which processing status gets recorded.
        boolean advanced = shipment.recordEvent(
                normalized, message.eventTime(), message.sequenceNumber(), now);

        EventProcessingStatus processingStatus =
                advanced ? EventProcessingStatus.APPLIED : EventProcessingStatus.SUPERSEDED;

        trackingEventRepository.save(TrackingEvent.builder()
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

        // The shipment is a managed entity: the status change flushes on commit, guarded by
        // @Version. No explicit save call, and no second write path to keep in sync.

        log.debug("Processed event {} for shipment {}: {} -> {} ({})",
                message.eventId(), shipment.getShipmentId(), message.eventType(), normalized,
                processingStatus);

        return new TrackingEventProcessingResult(
                message.eventId(),
                shipment.getShipmentId(),
                normalized,
                shipment.getCurrentStatus(),
                processingStatus);
    }

    /**
     * Validation is explicit here rather than via {@code @Valid} on the listener parameter.
     *
     * <p>A listener-level annotation raises a Spring Messaging exception wrapped in a
     * {@code ListenerExecutionFailedException}, which Stage 3 would have to unwrap to decide
     * "permanently invalid, dead letter it" versus "transient, retry it". Failing here with a
     * dedicated exception type makes that decision a straightforward {@code instanceof}.
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
