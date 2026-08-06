package ca.vm.parcelflow.support;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.tracking.messaging.CarrierTrackingEventMessage;
import java.time.Instant;
import java.util.UUID;

/** Builds valid carrier event messages so each test only states the field it cares about. */
public final class CarrierEvents {

    public static final Instant T0 = Instant.parse("2026-08-01T10:00:00Z");

    private CarrierEvents() {
    }

    public static Builder swiftPost(UUID shipmentId, String eventType, long sequenceNumber) {
        return new Builder()
                .shipmentId(shipmentId)
                .carrierCode(CarrierCode.SWIFTPOST)
                .eventType(eventType)
                .sequenceNumber(sequenceNumber)
                .eventTime(T0.plusSeconds(sequenceNumber * 3600));
    }

    public static Builder pacifica(UUID shipmentId, String eventType, long sequenceNumber) {
        return new Builder()
                .shipmentId(shipmentId)
                .carrierCode(CarrierCode.PACIFICA)
                .eventType(eventType)
                .sequenceNumber(sequenceNumber)
                .eventTime(T0.plusSeconds(sequenceNumber * 3600));
    }

    public static final class Builder {

        private UUID eventId = UUID.randomUUID();
        private Integer schemaVersion = CarrierTrackingEventMessage.SUPPORTED_SCHEMA_VERSION;
        private UUID shipmentId;
        private String trackingNumber = "SP-TEST-1";
        private CarrierCode carrierCode = CarrierCode.SWIFTPOST;
        private String eventType = "SP_TRANSIT";
        private Instant eventTime = T0;
        private Long sequenceNumber = 1L;
        private String location = "Rivermouth";
        private String description = "Scan recorded";
        private String correlationId = "test-correlation";

        public Builder eventId(UUID eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder schemaVersion(Integer schemaVersion) {
            this.schemaVersion = schemaVersion;
            return this;
        }

        public Builder shipmentId(UUID shipmentId) {
            this.shipmentId = shipmentId;
            return this;
        }

        public Builder trackingNumber(String trackingNumber) {
            this.trackingNumber = trackingNumber;
            return this;
        }

        public Builder carrierCode(CarrierCode carrierCode) {
            this.carrierCode = carrierCode;
            return this;
        }

        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder eventTime(Instant eventTime) {
            this.eventTime = eventTime;
            return this;
        }

        public Builder sequenceNumber(Long sequenceNumber) {
            this.sequenceNumber = sequenceNumber;
            return this;
        }

        public Builder location(String location) {
            this.location = location;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public CarrierTrackingEventMessage build() {
            return new CarrierTrackingEventMessage(
                    eventId, schemaVersion, shipmentId, trackingNumber, carrierCode, eventType,
                    eventTime, sequenceNumber, location, description, correlationId);
        }
    }
}
