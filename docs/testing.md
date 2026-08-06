# Testing

> ParcelFlow is an independent portfolio and training project. It is not affiliated with or based on
> proprietary systems from any delivery carriers or ecommerce retailers.

```bash
./gradlew test
```

Requires a running Docker daemon. Integration tests start real PostgreSQL 17 and Redpanda
containers.

**Stage 2: 109 tests, all passing** (35 from Stage 1, 74 added).

---

## Principles

**No context-loads test.** There is no `@Test void contextLoads() {}`. Every test asserts a
behaviour. The Spring context is exercised as a side effect of tests that assert something real,
which catches wiring breakage anyway.

**No mocked database, and no embedded broker.** The unique constraint, the `@Version` increment, and
`ddl-auto: validate` against the Flyway schema cannot be verified against an in-memory database or a
mock repository. Nor can partition assignment, consumer group formation, or the behaviour of the
container's error handler be verified against a mock. Those are precisely the behaviours the system
depends on, so the tests use the real engine and a real broker.

**No `Thread.sleep`.** Asynchronous assertions use Awaitility with a bounded timeout. A sleep is
either too short on a loaded machine or wasted time on a fast one.

**Hand-written JSON on the wire.** The Kafka test publishes a literal JSON document rather than
serializing the consumer's own record type. Serializing a shared class would let producer and
consumer agree with each other by construction and prove nothing about the wire format; a literal
document catches a renamed field, a changed date encoding, or an enum spelled differently.

**One container per JVM.** `PostgresIntegrationTest` starts the container from a static initializer
rather than through the `@Testcontainers` extension. The extension scopes a static `@Container` to a
single test class, so every integration test class would pay a fresh container start. Starting it
once per JVM means all classes share one, and Spring's context cache keeps the application context
warm alongside it. `KafkaIntegrationTest` extends it and adds Redpanda the same way, so only the
tests that need a broker pay for one.

**Fixed timestamps.** The domain takes `Instant` parameters instead of calling `Instant.now()`, so
ordering tests assert against exact instants with no tolerance windows and no flakiness.

---

## What each test class proves

### `ShipmentTest` — 14 tests, no Spring, no database

The ordering rules that every distributed scenario in the project relies on. Pure logic, so it runs
in milliseconds and fails with an unambiguous cause.

| Group | Assertion |
|---|---|
| initial state | a new shipment is `LABEL_CREATED` with nothing applied |
| in-order | the first event always applies; higher sequence advances; a repeated `ARRIVED_AT_FACILITY` at a new facility still advances |
| out-of-order | an older `IN_TRANSIT` after `OUT_FOR_DELIVERY` does **not** rewind the status |
| out-of-order | a replayed sequence number does not advance |
| out-of-order | sequence number wins when a carrier's scanner clock is skewed backwards |
| out-of-order | `eventTime` breaks ties when two events share a sequence number |
| out-of-order | a rejected event leaves `updatedAt` untouched |
| terminal | `DELIVERED` is the only terminal status |
| terminal | a backfilled scan with sequence 99 cannot un-deliver a parcel delivered at sequence 8 |
| terminal | a `DELIVERED` event arriving early freezes the shipment against later in-between events |
| PII | `toString()` never contains the customer reference |

### `ShipmentPersistenceIntegrationTest` — 6 tests, real PostgreSQL

What only a real database can confirm:

- A shipment round-trips with every field intact, including `TIMESTAMPTZ` precision and `DATE`.
- The composite unique constraint rejects a duplicate `(carrier, tracking number)` — proving the
  409 path is backed by the database, not a race-prone `SELECT`.
- The same tracking number **is** allowed for a different carrier — proving the constraint is
  composite, not global.
- `@Version` increments from 0 to 1 on update, which is the mechanism optimistic locking depends on.
- Retailer queries are scoped, ordered newest-first, and filter by status.

Implicitly, every test in this class proves that `ddl-auto: validate` accepts the Flyway schema — a
mapping drift breaks the whole class.

### `ShipmentApiIntegrationTest` — 12 tests, full stack minus the socket

Real controller, real bean validation, real Jackson 3, real PostgreSQL.

| Case | Expected |
|---|---|
| register a shipment | `201`, `Location` header, body with `LABEL_CREATED` and `version: 0` |
| follow the `Location` header | `200` and the same parcel — the header is genuinely resolvable |
| register the same tracking number twice | `409` `problem+json` with `type`, `carrierCode`, `trackingNumber`; still exactly one row |
| missing required fields | `400` with a per-field `errors` map |
| unknown `carrierCode` | `400`, and the body contains neither `CarrierCode` nor a package name — no parser internals leak |
| unknown shipment id | `404` `problem+json` carrying the id |
| malformed shipment id | `400`, not `404` and not `500` |
| retailer listing | scoped to the retailer, correctly paginated across two pages, `hasNext` accurate |
| `?status=` filter | narrows the result set |
| unknown retailer | `200` with an empty page, not a `404` |
| `size=5000` | `400` — rejected, not silently clamped |
| `page=-1` | `400` |

The registration test also asserts the response body contains neither `customerId` nor its value,
enforcing the write-only rule at the contract level.

### `OperationalEndpointsIntegrationTest` — 4 tests

Guards the surfaces that break silently during dependency upgrades:

- `/actuator/health` reports `UP` **and** includes the `db` contributor.
- Liveness and readiness probes are exposed — the Compose health check depends on readiness.
- `/actuator/prometheus` serves metrics.
- `/v3/api-docs` generates and describes all three Stage 1 paths by their exact templates. This one
  earns its place: springdoc must stay compatible with Spring Boot 4's Jackson 3 default, and a
  version mismatch produces a `400` from that endpoint rather than a compilation error.

### `CarrierEventNormalizerTest` — 30 tests, no Spring

Parameterized over every code in both carrier vocabularies, in both directions:

- Each of SwiftPost's eight codes maps to the expected status, and each of Pacifica's eight does too.
- Every normalized status is reachable from some SwiftPost code — the mapping has no holes.
- `DELIVERY_FAILED` is `DELIVERY_ATTEMPTED` and not terminal; `COMPLETE` is `DELIVERED`. Both are
  cases no string transform would get right.
- A Pacifica code offered to SwiftPost is rejected — vocabularies are not shared.
- Casing and surrounding whitespace are tolerated; blank, null and invented codes are not.
- The exception carries the carrier and the offending code, which Stage 3's dead letter record needs.

### `CarrierEventNormalizersTest` — 6 tests

Registry routing and misconfiguration:

- Each carrier's code routes to that carrier's normalizer.
- `NORDEX` and `METROLINK` — real `CarrierCode` values with no normalizer — produce
  `UnsupportedCarrierException`, deliberately distinct from `UnknownCarrierEventTypeException`. One
  is a configuration gap, the other is a message to investigate.
- Two normalizers claiming one carrier fail at construction rather than resolving by bean-ordering
  luck.

### `TrackingEventProcessorIntegrationTest` — 13 tests, real PostgreSQL, no broker

The pipeline driven directly. Bypassing Kafka is the point: none of these assertions are about
transport, and the test runs in a fraction of a second as a result.

| Case | Expected |
|---|---|
| valid event | stored with raw *and* normalized type, location, description, correlation id, `receivedAt` |
| status update | shipment status, `lastEventTime`, `lastSequenceNumber` advance; `@Version` → 1 |
| full journey | six SwiftPost scans walk the parcel to `DELIVERED` |
| late event | stored as `SUPERSEDED`, with its own normalized reading; shipment does **not** rewind |
| second carrier | Pacifica's vocabulary reaches the same normalized statuses |
| unknown shipment | `ShipmentNotFoundException`, nothing written |
| carrier mismatch | `CarrierMismatchException`, nothing written |
| unknown event code | `UnknownCarrierEventTypeException`, nothing written, status unchanged |
| unsupported carrier | `UnsupportedCarrierException`, nothing written |
| `schemaVersion: 99` | `InvalidCarrierEventException` naming the schema version |
| blank `correlationId` | `InvalidCarrierEventException` naming the field |
| `sequenceNumber: 0` | rejected — a non-positive counter would break ordering comparisons |
| absent optionals | `location` and `description` may be null |

The "nothing written" assertions matter: they prove the transaction rolls back rather than leaving a
history row for an event that was never applied.

### `CarrierTrackingEventListenerIntegrationTest` — 3 tests, real Redpanda + PostgreSQL

The transport path end to end, with Awaitility polling for the asynchronous result.

- A JSON document published to the real topic is consumed, normalized, persisted with every field
  intact, and moves the shipment to `OUT_FOR_DELIVERY`.
- A five-event sequence keyed by shipment id walks the parcel to `DELIVERED` in order.
- **A record the consumer cannot process does not stall the partition.** An event with an unknown
  carrier code is published, then a valid one; the valid one still arrives. This is the property
  that keeps a poison pill from halting ingestion for every parcel on that partition, and it is what
  Stage 3 upgrades from "retried then dropped" to "dead-lettered with context".

### `TrackingHistoryApiIntegrationTest` — 9 tests

| Case | Expected |
|---|---|
| ingested 5, 1, 3 | returned 1, 3, 5 — insertion order does not leak into the response |
| equal event times | tie-broken by sequence number |
| entry shape | both `normalizedEventType` and `carrierEventType`, plus location, description, correlation id; internal `id` absent |
| superseded events | present in history alongside applied ones, correctly labelled |
| pagination | envelope counts correct across three pages, first and last entries right |
| no events yet | `200` with an empty page, not a `404` |
| unknown shipment | `404` `problem+json` carrying the id |
| malformed id | `400` |
| `size=5000` | `400` |

### Simulator tests — 13 tests, no Spring

- Every carrier's journey starts at label creation and ends at delivery, with a plausible gap
  between scans.
- The two carriers' vocabularies do not overlap — otherwise the simulator would not be testing
  normalization at all.
- Sequence numbers start at 1 and increase by one; event times are sorted and span more than a day,
  ending at "now".
- Event ids are unique, `schemaVersion` is 1, and the correlation id is shared across the run.
- **Carrier-native codes are published, never normalized ones** — asserted explicitly, because a
  simulator that emitted `LABEL_CREATED` would silently stop exercising the normalization layer.
- Command-line parsing: defaults, every missing required argument named, unknown carrier rejected
  *before* connecting to Kafka, malformed UUID / delay / scenario each reported with the input.

---

## Coverage against the brief

| Required test | Status |
|---|---|
| Shipment status transition | ✅ `ShipmentTest` |
| Out-of-order events | ✅ `ShipmentTest` (5 cases), `TrackingEventProcessorIntegrationTest` |
| PostgreSQL integration | ✅ `ShipmentPersistenceIntegrationTest`, `TrackingEventProcessorIntegrationTest` |
| REST API | ✅ `ShipmentApiIntegrationTest`, `TrackingHistoryApiIntegrationTest` |
| Carrier event normalization | ✅ `CarrierEventNormalizerTest` (both carriers) |
| Unsupported carrier / event type | ✅ `CarrierEventNormalizersTest` |
| Successful event processing | ✅ `TrackingEventProcessorIntegrationTest` |
| Shipment status update | ✅ `TrackingEventProcessorIntegrationTest` |
| Tracking history ordering | ✅ `TrackingHistoryApiIntegrationTest` |
| Kafka integration (Testcontainers) | ✅ `CarrierTrackingEventListenerIntegrationTest` |
| Optimistic locking | ◐ `@Version` increment verified; the concurrent-conflict test lands in Stage 3 with the retry policy |
| Duplicate events | Stage 3 |
| Invalid messages (DLQ routing) | ◐ rejection and classification tested; dead-letter publication is Stage 3 |
| Dead Letter Queue | Stage 3 |
| Redis integration | Stage 3 |

---

## Configuration

`src/test/resources/application-test.yml` keeps `ddl-auto: validate` in tests deliberately. Letting
Hibernate create the schema in tests would mean the tests validate the entities against themselves
and never against the migrations that actually run in production.
