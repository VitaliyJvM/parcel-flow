package ca.vm.parcelflow.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.vm.parcelflow.carrier.CarrierCode;
import ca.vm.parcelflow.shipment.domain.Shipment;
import ca.vm.parcelflow.shipment.domain.ShipmentStatus;
import ca.vm.parcelflow.support.PostgresIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies the mapping and the migration against a real PostgreSQL instance.
 *
 * <p>The value here is what an in-memory database cannot check: that {@code ddl-auto: validate}
 * accepts the Flyway schema, that the composite unique constraint actually fires, and that
 * {@code @Version} increments on update.
 */
@SpringBootTest
@ActiveProfiles("test")
class ShipmentPersistenceIntegrationTest extends PostgresIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-08-01T10:00:00Z");

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void clearShipments() {
        shipmentRepository.deleteAll();
    }

    @Test
    @DisplayName("a shipment round-trips through PostgreSQL with all fields intact")
    void roundTripsThroughDatabase() {
        Shipment saved = shipmentRepository.saveAndFlush(shipment("SP-ROUNDTRIP", T0));

        Shipment loaded = shipmentRepository.findById(saved.getShipmentId()).orElseThrow();

        assertThat(loaded.getRetailerId()).isEqualTo("retailer-1");
        assertThat(loaded.getCustomerId()).isEqualTo("cust-1");
        assertThat(loaded.getTrackingNumber()).isEqualTo("SP-ROUNDTRIP");
        assertThat(loaded.getCarrierCode()).isEqualTo(CarrierCode.SWIFTPOST);
        assertThat(loaded.getCurrentStatus()).isEqualTo(ShipmentStatus.LABEL_CREATED);
        assertThat(loaded.getEstimatedDeliveryDate()).isEqualTo(LocalDate.parse("2026-08-05"));
        assertThat(loaded.getCreatedAt()).isEqualTo(T0);
        assertThat(loaded.getVersion()).isZero();
    }

    @Test
    @DisplayName("the unique constraint rejects the same tracking number for the same carrier")
    void rejectsDuplicateTrackingNumberForSameCarrier() {
        shipmentRepository.saveAndFlush(shipment("SP-DUP", T0));

        assertThatThrownBy(() -> shipmentRepository.saveAndFlush(shipment("SP-DUP", T0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the same tracking number is allowed for a different carrier")
    void allowsSameTrackingNumberForDifferentCarrier() {
        shipmentRepository.saveAndFlush(
                shipment("SHARED-1", T0, CarrierCode.SWIFTPOST));

        Shipment other = shipmentRepository.saveAndFlush(
                shipment("SHARED-1", T0, CarrierCode.NORDEX));

        assertThat(other.getShipmentId()).isNotNull();
        assertThat(shipmentRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("@Version increments when the shipment is updated")
    void versionIncrementsOnUpdate() {
        UUID shipmentId = shipmentRepository.saveAndFlush(shipment("SP-VERSION", T0))
                .getShipmentId();

        transactionTemplate.executeWithoutResult(status -> {
            Shipment managed = shipmentRepository.findById(shipmentId).orElseThrow();
            managed.recordEvent(ShipmentStatus.PICKED_UP, T0.plusSeconds(60), 1, T0.plusSeconds(61));
        });

        Shipment reloaded = shipmentRepository.findById(shipmentId).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(1L);
        assertThat(reloaded.getCurrentStatus()).isEqualTo(ShipmentStatus.PICKED_UP);
        assertThat(reloaded.getLastSequenceNumber()).isEqualTo(1L);
    }

    @Test
    @DisplayName("lookup by carrier and tracking number finds the shipment")
    void findsByCarrierAndTrackingNumber() {
        shipmentRepository.saveAndFlush(shipment("SP-LOOKUP", T0));

        assertThat(shipmentRepository
                        .findByCarrierCodeAndTrackingNumber(CarrierCode.SWIFTPOST, "SP-LOOKUP"))
                .isPresent();
        assertThat(shipmentRepository
                        .findByCarrierCodeAndTrackingNumber(CarrierCode.NORDEX, "SP-LOOKUP"))
                .isEmpty();
    }

    @Test
    @DisplayName("retailer queries are scoped, ordered newest first, and filterable by status")
    void queriesRetailerShipments() {
        shipmentRepository.saveAndFlush(shipment("SP-OLD", T0));
        shipmentRepository.saveAndFlush(shipment("SP-NEW", T0.plusSeconds(600)));
        shipmentRepository.saveAndFlush(
                shipment("NX-OTHER", T0, CarrierCode.NORDEX, "retailer-2"));

        var page = shipmentRepository.findByRetailerId(
                "retailer-1", PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent())
                .extracting(Shipment::getTrackingNumber)
                .containsExactly("SP-NEW", "SP-OLD");

        var filtered = shipmentRepository.findByRetailerIdAndCurrentStatus(
                "retailer-1", ShipmentStatus.DELIVERED, PageRequest.of(0, 10));
        assertThat(filtered.getTotalElements()).isZero();
    }

    private static Shipment shipment(String trackingNumber, Instant createdAt) {
        return shipment(trackingNumber, createdAt, CarrierCode.SWIFTPOST, "retailer-1");
    }

    private static Shipment shipment(
            String trackingNumber, Instant createdAt, CarrierCode carrierCode) {
        return shipment(trackingNumber, createdAt, carrierCode, "retailer-1");
    }

    private static Shipment shipment(
            String trackingNumber, Instant createdAt, CarrierCode carrierCode, String retailerId) {
        return Shipment.register(
                UUID.randomUUID(),
                retailerId,
                "cust-1",
                trackingNumber,
                carrierCode,
                LocalDate.parse("2026-08-05"),
                createdAt);
    }
}
