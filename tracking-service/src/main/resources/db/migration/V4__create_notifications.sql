-- Stage 3: notification records for delivery milestones.
--
-- Records only. Nothing in ParcelFlow dispatches email or SMS; a real dispatcher would read this
-- table and move rows out of PENDING.

CREATE TABLE notifications
(
    notification_id   UUID        NOT NULL,
    shipment_id       UUID        NOT NULL,

    -- The tracking event that caused this notification. Keeping the causal link means a support
    -- engineer can answer "why did the customer get this?" with a single join.
    source_event_id   UUID        NOT NULL,

    notification_type VARCHAR(32) NOT NULL,
    channel           VARCHAR(16) NOT NULL,
    status            VARCHAR(16) NOT NULL,
    created_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_notifications PRIMARY KEY (notification_id),

    -- One notification per (shipment, causing event). This is the durable guarantee that a
    -- redelivered carrier event cannot notify a customer twice, independent of what the consumer
    -- does. The application also short-circuits on duplicates, but a constraint is what makes it
    -- true under a race between two consumer threads.
    CONSTRAINT uq_notifications_shipment_source_event UNIQUE (shipment_id, source_event_id),

    CONSTRAINT fk_notifications_shipment
        FOREIGN KEY (shipment_id) REFERENCES shipments (shipment_id) ON DELETE CASCADE
);

-- Serves GET /api/shipments/{id}/notifications. notification_id is the last sort key so that
-- notifications created in the same transaction, sharing created_at, still page deterministically.
CREATE INDEX idx_notifications_shipment_created
    ON notifications (shipment_id, created_at, notification_id);
