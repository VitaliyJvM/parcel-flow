-- Stage 3: durable record of events that could not be processed.
--
-- Written when the Kafka error handler gives up, immediately before the record is published to the
-- dead letter topic. The DLT holds the message; this table holds the explanation and the operator
-- workflow around it.

CREATE TABLE failed_events
(
    failed_event_id    UUID          NOT NULL,

    -- Null when the payload could not be parsed far enough to find an event id. Postgres treats
    -- NULLs as distinct in a UNIQUE constraint, so several unparseable payloads coexist while a
    -- named event still gets exactly one row.
    event_id           UUID,
    shipment_id        UUID,

    -- TEXT, not JSONB, on purpose: a payload that failed to deserialize is frequently not valid
    -- JSON, and JSONB would reject the very rows most worth keeping.
    payload            TEXT          NOT NULL,

    error_category     VARCHAR(32)   NOT NULL,
    error_type         VARCHAR(255)  NOT NULL,

    -- Bounded. The exception message is kept; the stack trace is not. A trace is unbounded, is
    -- mostly framework frames, and belongs in the log stream where it can be sampled and expired.
    error_message      VARCHAR(2000),

    retry_count        INTEGER       NOT NULL,
    status             VARCHAR(16)   NOT NULL,

    original_topic     VARCHAR(255)  NOT NULL,
    original_partition INTEGER       NOT NULL,
    original_offset    BIGINT        NOT NULL,

    first_failed_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_failed_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    -- Guards the manual retry transition: two operators clicking retry at the same moment must not
    -- both start reprocessing.
    version            BIGINT        NOT NULL,

    CONSTRAINT pk_failed_events PRIMARY KEY (failed_event_id),
    CONSTRAINT uq_failed_events_event_id UNIQUE (event_id)
);

-- Serves GET /api/admin/failed-events, which filters by status and lists newest failure first.
CREATE INDEX idx_failed_events_status_last_failed
    ON failed_events (status, last_failed_at DESC);

CREATE INDEX idx_failed_events_last_failed
    ON failed_events (last_failed_at DESC);
