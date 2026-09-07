package ca.vm.parcelflow.tracking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The parts of the entity that owe nothing to JPA: identity, and the string it renders itself as.
 *
 * <p>No database. Everything here is decided by the class, so a Testcontainers start would only
 * make the same assertions slower.
 */
class TrackingEventTest {

    private static final UUID EVENT_ID = UUID.fromString("8b0d17f1-3f6a-4b52-9b3f-2c0c1c6a5f11");
    private static final UUID SHIPMENT_ID = UUID.fromString("00c5356b-8b0b-47e5-b88c-1e504dd2bf34");

    /**
     * A regression test with a specific history. {@code toString} was written as
     * {@code "first half" + "second half".formatted(args)}, which formats the second literal alone
     * — handing its {@code %d} a {@code UUID} and throwing {@code IllegalFormatConversionException}
     * on every call. Nothing caught it because nothing called {@code toString}; the first thing
     * that would have was a log line in a failure path.
     */
    @Test
    @DisplayName("toString renders every field it names, rather than throwing on the %d placeholder")
    void toStringRendersEveryFieldItNames() {
        String rendered = event(EVENT_ID, 7L).toString();

        assertThat(rendered)
                .startsWith("TrackingEvent[")
                .endsWith("]")
                .contains("eventId=" + EVENT_ID)
                .contains("shipmentId=" + SHIPMENT_ID)
                .contains("carrierCode=SWIFTPOST")
                .contains("carrierEventType=SP_OFD")
                .contains("normalizedEventType=OUT_FOR_DELIVERY")
                .contains("sequenceNumber=7")
                .contains("processingStatus=APPLIED")
                .doesNotContain("%s", "%d");
    }

    @Test
    @DisplayName("identity is the carrier's eventId, so a stored and an in-flight copy are equal")
    void identityIsTheCarrierEventId() {
        TrackingEvent event = event(EVENT_ID, 1L);
        TrackingEvent sameEventDifferentDetails = event(EVENT_ID, 99L);
        TrackingEvent otherEvent = event(UUID.randomUUID(), 1L);

        assertThat(event).isEqualTo(sameEventDifferentDetails);
        assertThat(event).hasSameHashCodeAs(sameEventDifferentDetails);
        assertThat(event).isNotEqualTo(otherEvent);
        assertThat(event).isNotEqualTo(null);
        assertThat(event).isNotEqualTo("not a tracking event");
    }

    private static TrackingEvent event(UUID eventId, long sequenceNumber) {
        return TrackingEvent.builder()
                .eventId(eventId)
                .shipmentId(SHIPMENT_ID)
                .trackingNumber("SP123456")
                .carrierCode(CarrierCode.SWIFTPOST)
                .carrierEventType("SP_OFD")
                .normalizedEventType(ShipmentStatus.OUT_FOR_DELIVERY)
                .eventTime(Instant.parse("2026-08-05T12:00:00Z"))
                .receivedAt(Instant.parse("2026-08-05T12:00:01Z"))
                .sequenceNumber(sequenceNumber)
                .location("Ashgrove")
                .description("Out for delivery")
                .correlationId("corr-1")
                .processingStatus(EventProcessingStatus.APPLIED)
                .build();
    }
}
