-- Stage 1: the shipment aggregate.
-- tracking_events and notifications arrive in their own migrations in Stage 2 and Stage 3.

CREATE TABLE shipments
(
    shipment_id             UUID         NOT NULL,
    retailer_id             VARCHAR(64)  NOT NULL,
    customer_id             VARCHAR(64)  NOT NULL,
    tracking_number         VARCHAR(128) NOT NULL,
    carrier_code            VARCHAR(32)  NOT NULL,
    current_status          VARCHAR(32)  NOT NULL,
    estimated_delivery_date DATE,
    last_event_time         TIMESTAMP(6) WITH TIME ZONE,
    last_sequence_number    BIGINT,
    version                 BIGINT       NOT NULL,
    created_at              TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_shipments PRIMARY KEY (shipment_id),

    -- A tracking number is unique within a carrier, not globally. This constraint is the
    -- authority for duplicate registration: it holds even when two requests race, which a
    -- SELECT-then-INSERT check cannot.
    CONSTRAINT uq_shipments_carrier_tracking_number UNIQUE (carrier_code, tracking_number)
);

-- Serves GET /api/retailers/{retailerId}/shipments, which sorts by created_at DESC.
CREATE INDEX idx_shipments_retailer_created_at
    ON shipments (retailer_id, created_at DESC);

-- Serves the same endpoint with the ?status= filter applied.
CREATE INDEX idx_shipments_retailer_status_created_at
    ON shipments (retailer_id, current_status, created_at DESC);
