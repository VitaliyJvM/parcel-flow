-- Stage 3: the third level of the event ordering rule.
--
-- Ordering compares sequence_number, then event_time, then received_at. The first two were already
-- stored; this column records when the winning event was ingested, so the final tie-break has
-- something to compare against instead of being implicitly "always accept".

ALTER TABLE shipments
    ADD COLUMN last_received_at TIMESTAMP(6) WITH TIME ZONE;

-- Backfill: for shipments that already applied an event, the ingest time of that event is the best
-- available answer. Shipments with no applied event keep NULL, which the ordering rule reads as
-- "nothing applied yet".
UPDATE shipments s
SET last_received_at = (SELECT MAX(e.received_at)
                        FROM tracking_events e
                        WHERE e.shipment_id = s.shipment_id
                          AND e.processing_status = 'APPLIED')
WHERE s.last_event_time IS NOT NULL;
