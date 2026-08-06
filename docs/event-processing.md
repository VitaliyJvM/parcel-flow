# Event processing

> ParcelFlow is an independent portfolio and training project. It is not affiliated with or based on
> proprietary systems from any delivery carriers or ecommerce retailers.

How a carrier scan becomes a status change. Sections marked *(Stage 3)* describe committed design
that is not implemented yet.

---

## 1. The event contract

Carrier events are published to `carrier-tracking-events` as JSON, keyed by shipment id.

| Field | Type | Required | Notes |
|---|---|:---:|---|
| `eventId` | UUID | ✔ | The carrier's identifier for this scan. Unique key of the history table. |
| `schemaVersion` | int | ✔ | Currently `1`. Any other value is rejected as permanently invalid. |
| `shipmentId` | UUID | ✔ | Must resolve to a registered shipment. |
| `trackingNumber` | string ≤128 | ✔ | Stored as sent, on the event row. |
| `carrierCode` | enum | ✔ | `SWIFTPOST`, `NORDEX`, `PACIFICA`, `METROLINK`. Must match the shipment. |
| `eventType` | string ≤64 | ✔ | The carrier's **own** code, not a normalized one. |
| `eventTime` | ISO-8601 instant | ✔ | UTC. When the carrier observed the scan. |
| `sequenceNumber` | long > 0 | ✔ | The carrier's per-shipment counter. Primary ordering authority. |
| `location` | string ≤255 | | Free text. |
| `description` | string ≤512 | | Free text. |
| `correlationId` | string ≤64 | ✔ | Propagated from the producer; ties a journey together across services. |

```json
{
  "eventId": "cc512789-b504-4f1b-b1ad-6c568f9823df",
  "schemaVersion": 1,
  "shipmentId": "1d485737-0830-4fe9-a083-45a40c7f26e2",
  "trackingNumber": "SP100000000042",
  "carrierCode": "SWIFTPOST",
  "eventType": "SP_OFD",
  "eventTime": "2026-08-05T20:07:08.084327625Z",
  "sequenceNumber": 7,
  "location": "Ashgrove",
  "description": "Out for delivery",
  "correlationId": "08ad3a97-8e5c-4b12-8ee5-80938efa139a"
}
```

### The contract is not a shared class

`CarrierTrackingEventMessage` is declared twice: once in `tracking-service`, once in
`carrier-simulator`. That is deliberate. A carrier does not compile against your classes — the
contract between you is the JSON document and the `schemaVersion` that describes it. Sharing a
module would make a schema change look like a compile step instead of a deployment ordering
problem, which is the exact problem `schemaVersion` exists to manage.

The cost is real: the two declarations must be kept in step by hand. That cost is the argument for
the schema registry listed under future improvements.

### Why the consumer ignores type headers

`spring.json.use.type.headers` is `false` and the deserializer is pinned to one class via
`spring.json.value.default.type`. Spring Kafka's JSON serializer will, by default, stamp the
producer's fully-qualified class name into a header and the consumer will instantiate whatever it
names. Trusting that means a producer chooses which class the consumer constructs — a
deserialization gadget in waiting, and here it would not even work, since the simulator's class
lives in a different package.

`spring.json.trusted.packages` names one package. Never `*`.

---

## 2. Normalization

Each carrier speaks its own vocabulary. One `CarrierEventNormalizer` bean per carrier, discovered by
Spring and indexed by `CarrierEventNormalizers`. Adding a carrier is a new class; there is no switch
statement and no registration list to update.

| Normalized | SwiftPost | Pacifica |
|---|---|---|
| `LABEL_CREATED` | `SP_CREATED` | `MANIFESTED` |
| `PICKED_UP` | `SP_PICKUP` | `COLLECTED` |
| `IN_TRANSIT` | `SP_TRANSIT` | `MOVING` |
| `ARRIVED_AT_FACILITY` | `SP_DEPOT` | `AT_TERMINAL` |
| `OUT_FOR_DELIVERY` | `SP_OFD` | `COURIER_ROUTE` |
| `DELAYED` | `SP_DELAY` | `EXCEPTION_DELAY` |
| `DELIVERY_ATTEMPTED` | `SP_ATTEMPT` | `DELIVERY_FAILED` |
| `DELIVERED` | `SP_DELIVERED` | `COMPLETE` |

Two Pacifica mappings show why this is not a string transform: `DELIVERY_FAILED` is an *attempt*,
not a terminal failure — the parcel is still in the network — and `COMPLETE` means delivered, which
nothing in the string suggests.

`NORDEX` and `METROLINK` are valid carrier codes with no normalizer. That produces
`UnsupportedCarrierException`, deliberately distinct from `UnknownCarrierEventTypeException`: the
first is a configuration gap to fill, the second is a message to investigate.

Casing and surrounding whitespace are tolerated. Anything else is an error the operator should see.

### Normalized type and shipment status are the same enum

`ShipmentStatus` is reused as `normalizedEventType` rather than declaring a parallel
`NormalizedEventType` with the same eight constants. A second enum would need a mapping function
that could only ever be the identity. If the two vocabularies ever diverge — an event that carries
information but implies no status — that is the moment to split them.

---

## 3. The pipeline

```
Kafka record
  │
  ├─ ErrorHandlingDeserializer → JacksonJsonDeserializer     unparseable JSON cannot stall the poll loop
  │
  ▼
CarrierTrackingEventListener            transport only: MDC, logging, delegation
  │
  ▼
TrackingEventProcessor    ── one @Transactional unit ────────────────────┐
  │  1. validate            schema version, then bean validation         │
  │  2. load shipment       404 if unknown                               │
  │  3. check carrier       reject if it disagrees with the shipment     │
  │  4. normalize           carrier code → ShipmentStatus                │
  │  5. Shipment.recordEvent(...)   → applied? or superseded?            │
  │  6. insert tracking_events row with the outcome                      │
  └─────────────────────────────────────────────────────────────────────┘
  │
  ▼
offset committed (ack-mode: record)
```

**Both writes are one transaction.** The history insert and the shipment update either both land or
neither does, so there is never a window in which a parcel shows a status no stored event justifies.
Both target the same database, so this needs no distributed coordination — the main reason ingestion
was not split into its own service.

**The offset commits after the transaction.** If the process dies in that gap the record is
redelivered. That is the correct trade: at-least-once with a duplicate is recoverable, at-most-once
with a lost delivery scan is not. Making redelivery harmless is Stage 3's job.

**Ordering is per parcel, not global.** Producers key every event by shipment id, so all events for
one parcel land on one partition and reach one consumer in production order. Events for different
parcels have no ordering relationship and need none.

### Business logic is not in the listener

The listener does MDC, one log line, and one delegation. Everything else is in
`TrackingEventProcessor`, which takes a plain message object and knows nothing about Kafka. That is
what lets the same path be driven from a test with no broker (`TrackingEventProcessorIntegrationTest`
does exactly this), from a replay tool, or from the Stage 3 admin retry endpoint.

There is no `ShipmentStatusUpdater` class. `Shipment.recordEvent` already is one, and wrapping it
would add a layer with no behaviour.

---

## 4. Applied vs superseded

`Shipment.recordEvent` returns whether the event moved the shipment forward. The answer is recorded
on the event row as `processing_status`:

- **`APPLIED`** — the shipment's status, `last_event_time` and `last_sequence_number` advanced.
- **`SUPERSEDED`** — stored, but the shipment did not change: the event lost the ordering comparison,
  or the shipment was already `DELIVERED`.

`SUPERSEDED` is **not** a failure. The event is a real observation and belongs in history; it simply
did not win. `GET /api/shipments/{id}/events` returns both, so a support engineer sees the late scan
that a customer is asking about even though it never changed the displayed status.

The ordering rules themselves live in the domain model and are documented in
[architecture.md](architecture.md#3-the-ordering-decision).

---

## 5. Error classification

Every failure mode has a distinct exception type, so Stage 3 can decide retry-versus-dead-letter with
an `instanceof` rather than string matching.

| Exception | Cause | Retrying helps? |
|---|---|:---:|
| `InvalidCarrierEventException` | Missing field, constraint violation, unknown `schemaVersion` | No |
| `UnknownCarrierEventTypeException` | Carrier sent a code with no mapping | No |
| `UnsupportedCarrierException` | No normalizer registered for the carrier | No — needs a deploy |
| `CarrierMismatchException` | Event's carrier disagrees with the shipment's | No |
| `ShipmentNotFoundException` | Event arrived before its shipment was registered | **Possibly** — a genuine race |
| `OptimisticLockingFailureException` | Two threads updated one shipment | **Yes** — immediately |
| Database/broker unavailable | Infrastructure | **Yes** — with backoff |

`ShipmentNotFoundException` is the interesting one: it is usually permanent (a bad shipment id) but
can be transient (the carrier's first scan beating the retailer's registration call). Stage 3 gives
it bounded retries before dead-lettering, unlike the rows above it.

### What Stage 2 does on failure

Nothing sophisticated, on purpose. Spring Kafka's default error handler retries the record a few
times and then logs it and moves on, so a bad record cannot stall a partition — verified by
`CarrierTrackingEventListenerIntegrationTest`. The record is dropped, not dead-lettered.

`carrier-tracking-events-dlt` is created so the topology is complete and visible to operators, but
nothing publishes to it yet.

---

## 6. Storage

`tracking_events` is append-only and immutable: no setters, and nothing updates a stored row. An
event is a statement about the past.

Both readings are kept. `carrier_event_type` is what the carrier sent; `normalized_event_type` is
what ParcelFlow concluded. Keeping the raw value is what makes a support conversation with the
carrier possible, and what allows a mapping bug to be re-run against stored history rather than lost.

Indexes:

| Index | Serves |
|---|---|
| `(shipment_id, event_time, sequence_number)` | The history endpoint, including its sort |
| `(tracking_number, event_time)` | Support lookups that start from a tracking number |
| `(received_at DESC)` | Operational queries over the recent ingest stream |

`UNIQUE (event_id)` exists from Stage 2. Kafka delivers at least once, so the same `eventId` will
eventually arrive twice; the constraint is the durable guarantee that history cannot contain it
twice, independent of what the consumer is doing. Stage 3 adds the application-side handling that
turns the resulting violation into a recorded duplicate instead of a processing failure.

---

## 7. Running the simulator

```bash
# Register a parcel, then replay a carrier's normal journey against it
SHIPMENT_ID=$(curl -s -X POST http://localhost:8080/api/shipments \
  -H 'Content-Type: application/json' \
  -d '{"retailerId":"retailer-42","customerId":"cust-9f13",
       "trackingNumber":"SP100000000042","carrierCode":"SWIFTPOST"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["shipmentId"])')

docker compose run --rm carrier-simulator \
  --shipment-id=$SHIPMENT_ID \
  --tracking-number=SP100000000042 \
  --carrier=SWIFTPOST \
  --scenario=NORMAL \
  --delay-ms=500
```

| Argument | Required | Default | Notes |
|---|:---:|---|---|
| `--shipment-id` | ✔ | | Must be a registered shipment |
| `--tracking-number` | ✔ | | |
| `--carrier` | ✔ | | `SWIFTPOST` or `PACIFICA` |
| `--scenario` | | `NORMAL` | Only `NORMAL` in Stage 2 |
| `--delay-ms` | | `500` | Wall-clock gap between publishes |
| `--correlation-id` | | random UUID | Shared by every event in the run |

The simulator publishes **carrier-native codes**, never normalized ones — otherwise the
normalization layer would never be exercised end to end. Event times are back-dated so the sequence
reads as a real multi-day journey ending now, rather than eight timestamps one second apart.

---

## 8. Deferred to Stage 3

- Duplicate detection: catching the `event_id` constraint violation and recording a duplicate.
- Out-of-order **scenarios** in the simulator. The out-of-order *rules* are implemented and tested;
  what is missing is a producer that deliberately violates ordering.
- Bounded retries with backoff, classified by the exception table above.
- Dead letter publication to `carrier-tracking-events-dlt` with error context.
- `POST /api/admin/failed-events/{eventId}/retry`.
- Notification records for milestone events.
- Redis caching of the shipment tracking response.
- Multiple consumer instances and a rebalance test.
