# Architecture

> ParcelFlow is an independent portfolio and training project. It is not affiliated with or based on
> proprietary systems from any delivery carriers or ecommerce retailers.

This document covers what exists after Stage 2 and the decisions behind it. Sections marked
*(planned)* describe committed design that later stages implement.

The event pipeline has its own document: [event-processing.md](event-processing.md).

---

## 1. Shape of the system

Two deployables:

| Application | Role |
|---|---|
| **tracking-service** | Modular monolith. Owns the shipment REST API and the carrier event consumer. |
| **carrier-simulator** | Command-line producer that publishes synthetic carrier events. Broken-event scenarios arrive in Stage 3. |

### Why one service and not several

The event pipeline's core step is: update the shipment, append tracking history, create a
notification record. Splitting those across services would mean either a distributed transaction or
an outbox and saga — a large amount of machinery bought with nothing, since all three writes target
the same database. Keeping them in one local transaction makes the interesting problems
(idempotency, ordering, concurrency) visible instead of burying them under coordination code.

The cost is that module boundaries are conventions rather than network boundaries. They are kept
honest by package structure and by never letting a controller reach into another module's internals.

### Module boundaries

```
ca.vm.parcelflow
├── shipment        the aggregate: entity, repository, service, REST API
├── tracking        carrier event ingestion, history, and the events endpoint
│   ├── domain          TrackingEvent, EventProcessingStatus
│   ├── messaging       Kafka listener and the wire contract
│   └── api             tracking history REST
├── carrier         carrier codes and per-carrier event normalizers
│   └── normalization   one strategy per carrier, plus the registry
├── notification    milestone rules and notification records            (Stage 3)
├── infrastructure  framework wiring: Clock, OpenAPI, Kafka topics
└── shared          cross-module API concerns: error handling, paging
```

Dependency direction: `tracking` → `carrier`, `shipment`, `notification`. Nothing depends on
`tracking`. `shared` and `infrastructure` are depended upon but depend on nothing in the domain.
`notification` never calls back into `tracking` — that would be the first sign the boundary is
rotting.

---

## 2. Data model

### `shipments` — implemented

| Column | Type | Notes |
|---|---|---|
| `shipment_id` | `UUID` | PK, assigned by the application before insert |
| `retailer_id` | `VARCHAR(64)` | |
| `customer_id` | `VARCHAR(64)` | Opaque retailer-side reference. Never logged, never returned by the API. |
| `tracking_number` | `VARCHAR(128)` | |
| `carrier_code` | `VARCHAR(32)` | Enum as string, not ordinal |
| `current_status` | `VARCHAR(32)` | Materialized from applied events |
| `estimated_delivery_date` | `DATE` | |
| `last_event_time` | `TIMESTAMPTZ` | Event time of the last **applied** event |
| `last_sequence_number` | `BIGINT` | Sequence of the last **applied** event |
| `version` | `BIGINT` | Optimistic lock |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | |

Constraints and indexes:

- `UNIQUE (carrier_code, tracking_number)` — a tracking number is unique *within* a carrier, not
  globally. This is the authority for duplicate registration.
- `(retailer_id, created_at DESC)` — serves the retailer listing.
- `(retailer_id, current_status, created_at DESC)` — serves the same listing with `?status=`.

Two fields deserve explanation because they are not in the original data sketch:

**`last_sequence_number`.** Deciding whether an event is newer than the current state requires
knowing the last sequence number applied. Storing it on the shipment makes that decision a single
row read instead of a query against tracking history.

**Enums stored as strings.** `@Enumerated(EnumType.STRING)` throughout. Ordinals break the moment
someone inserts a constant in the middle of the enum, and they make the table unreadable in `psql`.

### `tracking_events` — implemented

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGINT` identity | Surrogate PK. Narrow and monotonic, for an append-only table. |
| `event_id` | `UUID` | The carrier's id. `UNIQUE` — the database-level idempotency guarantee. |
| `shipment_id` | `UUID` | FK to `shipments`, `ON DELETE CASCADE`. |
| `tracking_number` | `VARCHAR(128)` | Denormalized; see below. |
| `carrier_code` | `VARCHAR(32)` | |
| `carrier_event_type` | `VARCHAR(64)` | The carrier's own code, as received. |
| `normalized_event_type` | `VARCHAR(32)` | ParcelFlow's reading of it. |
| `event_time` / `received_at` | `TIMESTAMPTZ` | Observed by the carrier / ingested by us. |
| `sequence_number` | `BIGINT` | The carrier's per-shipment counter. |
| `location` / `description` | text | Optional, free text. |
| `correlation_id` | `VARCHAR(64)` | Propagated from the producer. |
| `processing_status` | `VARCHAR(32)` | `APPLIED` or `SUPERSEDED`. |

Append-only and immutable: the entity has no setters and nothing updates a stored row. An event is a
statement about the past, so correcting one would mean rewriting history rather than appending to it.

**Why both event types are stored.** The normalized value drives the API and the shipment state; the
raw value is what makes a support conversation with the carrier possible, and what allows a mapping
bug to be re-run against stored history instead of lost.

**Why `tracking_number` is denormalized.** Carrier support queries arrive as "what happened to
SP123456?". Storing the number the carrier itself used means the lookup needs no join and still
works if the shipment record is later corrected.

**Why `shipment_id` is a plain UUID, not `@ManyToOne`.** An association would add lazy loading and
cascade semantics to an append-only table for no gain: the write path already holds the `Shipment`
it needs, and the read path never navigates back.

Indexes: `(shipment_id, event_time, sequence_number)` for the history endpoint including its sort,
`(tracking_number, event_time)` for support lookups, `(received_at DESC)` for operational queries
over the recent ingest stream.

### `notifications` *(planned, Stage 3)*

One row per milestone crossed. Records only; nothing is delivered.

---

## 3. The ordering decision

This is the heart of the project, and it is deliberately in the domain model rather than a service:
`Shipment.recordEvent(status, eventTime, sequenceNumber, now)`.

```
if current status is terminal        -> reject  (DELIVERED is sticky)
if no event applied yet             -> accept  (first event always wins)
if sequence numbers differ          -> accept iff incoming > last applied
else (sequence numbers equal)       -> accept iff eventTime is after last applied
```

A rejected event returns `false`. That is **not an error**: the event is still appended to tracking
history, it just does not move `currentStatus`. This is exactly the scenario in the brief — an older
`IN_TRANSIT` arriving after `OUT_FOR_DELIVERY` leaves the parcel `OUT_FOR_DELIVERY` while still
appearing in history.

### Why sequence number outranks event time

Event times come from handheld scanners whose clocks drift and which frequently stamp consecutive
scans with the same second. Sequence numbers are integers assigned by the carrier in the order it
actually observed the parcel. When the two disagree, the sequence number is the better witness. Event
time is kept as a tie-breaker for carriers that reuse a sequence number.

### Why statuses are not ranked

The obvious-looking alternative is to give each status an integer rank and let the higher rank win.
It is wrong. `DELAYED` and `DELIVERY_ATTEMPTED` are exception states that can occur at several points
in a journey, and `ARRIVED_AT_FACILITY` legitimately repeats at every sorting hub. Any total order
over these statuses is a false model. The only ordering property that genuinely belongs to a status
is terminality.

### Why `DELIVERED` is sticky

Carriers backfill scans. A parcel delivered at sequence 8 can receive an `IN_TRANSIT` scan stamped
sequence 12 hours later. Without a terminal rule the parcel would visibly un-deliver, which is worse
than dropping the event. ParcelFlow has no post-delivery states (no returns, no re-shipment), so the
rule is safe within its scope — and it is the first thing to revisit if returns are ever added.

---

## 4. Concurrency control

Two consumer threads can process events for the same parcel simultaneously. The shipment row is the
contended resource.

**Mechanism: JPA optimistic locking** via `@Version`. Each update carries
`WHERE shipment_id = ? AND version = ?`; the loser gets zero affected rows and Spring raises
`OptimisticLockingFailureException`.

Why optimistic rather than `SELECT ... FOR UPDATE`:

- Conflicts are rare. Two events for the *same parcel* arriving close enough to overlap is unusual;
  events for *different* parcels never contend. Paying a lock on every update to protect against a
  rare case is the wrong trade.
- Pessimistic locks held across an event-processing transaction create a queue of consumer threads
  behind one slow parcel, and open the door to deadlocks when lock order varies.
- The retry is safe. The read-decide-write cycle re-reads the shipment, re-runs the ordering
  decision, and writes again. Because `recordEvent` is a pure function of (current state, incoming
  event), replaying it after a conflict produces the correct answer — the same property that makes
  the whole pipeline idempotent.

Retry of the conflict is wired in Stage 3, where it belongs with the rest of the retry policy.

---

## 5. Transaction boundaries

Transactions begin and end in `ShipmentService`, never in a controller and never in the domain
model. `spring.jpa.open-in-view` is `false`, so no transaction or connection is held open for the
duration of an HTTP response — a request that serializes slowly cannot hold a pooled connection
hostage.

One detail worth pointing out: `registerShipment` uses `saveAndFlush`, not `save`. With `save`, the
`INSERT` is deferred to commit, which happens *after* the service method returns — so the unique
constraint violation would surface outside the `try` block and the client would get a `500` instead
of a `409`. Flushing inside the transaction puts the exception where it can be translated.

Event ingestion has its own boundary in `TrackingEventProcessor.process`, which is one transaction
covering the history insert and the shipment update together. There is no window in which a parcel
shows a status that no stored event justifies. Both writes target the same database, so this needs
no distributed coordination — the concrete payoff of the single-service decision in section 1.

The shipment is a managed entity inside that transaction, so `recordEvent` flushes on commit under
`@Version` guard. There is no explicit `save` call for it and therefore no second write path to keep
in sync.

---

## 6. API design

- **RFC 9457 problem details** for every error, with a stable `type` URI per error kind so clients
  branch on a URI rather than a message string.
- **Pagination as explicit validated parameters** (`page`, `size` with `@Min`/`@Max`) rather than an
  injected `Pageable`. The page-size ceiling becomes part of the published contract and appears in
  the OpenAPI document, instead of depending on a framework default that a `size=100000` request
  might slip past.
- **Owned pagination envelope** (`PageResponse`) rather than serializing Spring Data's `Page`, whose
  JSON shape is an implementation detail that has changed across versions.
- **`customerId` is write-only.** Accepted at registration, never returned, never logged. It is also
  excluded from `Shipment.toString()` so it cannot leak into a log line by accident, which is
  verified by a test.
- **No business logic in controllers.** Controllers bind, delegate, and map to a response record.
- **Immutable records** for every DTO.
- **Injected `Clock`.** Nothing in the domain calls `Instant.now()`, so ordering behaviour is
  testable at exact timestamps.
- **Both readings exposed.** A tracking history entry carries `normalizedEventType` *and*
  `carrierEventType`, so a consumer can render the normalized value while a support engineer sees
  the raw scan. The internal surrogate key is not exposed — `eventId` is the identifier that means
  something outside the process.
- **Fixed ordering on history.** The events endpoint does not accept a sort parameter. The order is
  part of what the endpoint means, and the composite index is built for exactly that order.

---

## 7. Schema management

Flyway owns the schema; Hibernate runs with `ddl-auto: validate` in both production and tests. A
mismatch between a migration and an entity mapping fails at startup rather than being silently
papered over by `ddl-auto: update`.

Migrations are added incrementally per stage — `V1__create_shipments.sql`,
`V2__create_tracking_events.sql`, and `notifications` in Stage 3 — rather than written as one upfront
schema. That is the discipline a real deployment needs, where the previous migration has already run
in production and cannot be edited.

---

## 8. Deployment

Multi-stage Docker build: a JDK 25 image compiles, a JRE 25 image runs, and the container runs as a
non-root user. Build scripts are copied before source so the Gradle distribution and dependency cache
sit in a layer that survives source edits.

`-XX:MaxRAMPercentage=75` rather than a fixed `-Xmx` so the heap tracks whatever memory limit the
container is given. `-XX:+ExitOnOutOfMemoryError` makes the orchestrator restart a process that has
lost its heap, instead of leaving it thrashing.

Compose health checks gate startup: the service waits for PostgreSQL's `pg_isready -U ... -d ...`
(the flags matter — without them it reports ready before the database exists), and the service's own
check hits `/actuator/health/readiness` rather than a TCP port, so it reports healthy only after
Flyway has migrated and the datasource answers.

---

## 9. Messaging

**Redpanda, not Apache Kafka.** Same protocol, same client library, same consumer semantics, but a
single container with no ZooKeeper or KRaft bootstrap and a roughly one-second start. That makes a
broker-backed integration test cheap enough to run on every build, and the same image is used in
Compose and in Testcontainers so tests and the local stack exercise the same broker.

**Topics are declared as `NewTopic` beans**, created by Spring's `KafkaAdmin` at startup before any
listener consumes. Relying on broker auto-create would give topics the broker's default partition
count, silently breaking the partitioning that per-shipment ordering depends on. Declaring them also
keeps the topology in source rather than in someone's shell history.

**Three partitions, keyed by shipment id.** All events for one parcel land on one partition and are
delivered to one consumer in production order. Ordering is guaranteed per parcel, never globally —
which is exactly the guarantee the domain needs, and why the out-of-order rules exist for everything
that crosses parcels or crosses a producer retry.

**`ack-mode: record`.** The offset is committed after the listener method returns normally, one
record at a time. Manual acknowledgement was considered and rejected: it adds a way to silently lose
an offset commit while buying nothing, since the unit of work is exactly one record and the
processor's transaction has already committed by the time the method returns.

The offset commits *after* the database transaction, so a crash in that gap causes redelivery. That
is the correct trade — at-least-once with a duplicate is recoverable, at-most-once with a lost
delivery scan is not. Stage 3 closes the loop by making reprocessing idempotent.

**Deserialization is pinned, not negotiated.** `spring.json.use.type.headers` is `false` and
`spring.json.value.default.type` names the class. Trusting the producer's type header means a
producer chooses which class the consumer instantiates. `spring.json.trusted.packages` names one
package; never `*`.

`ErrorHandlingDeserializer` wraps the JSON deserializer so an unparseable payload becomes a failed
record the container can handle, rather than an exception inside the poll loop that stalls the
partition forever.
