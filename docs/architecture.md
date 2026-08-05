# Architecture

> ParcelFlow is an independent portfolio and training project. It is not affiliated with or based on
> proprietary systems from any delivery carriers or ecommerce retailers.

This document covers what exists after Stage 1 and the decisions behind it. Sections marked
*(planned)* describe committed design that later stages implement.

---

## 1. Shape of the system

Two deployables:

| Application | Role |
|---|---|
| **tracking-service** | Modular monolith. Owns the shipment REST API and, from Stage 2, the carrier event consumer. |
| **carrier-simulator** | Command-line producer that publishes synthetic carrier events, including deliberately broken ones. *(Stage 2)* |

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
├── tracking        carrier event ingestion and tracking history        (Stage 2)
├── carrier         carrier codes and per-carrier event normalizers     (Stage 2)
├── notification    milestone rules and notification records            (Stage 3)
├── infrastructure  framework wiring: Clock, OpenAPI, Kafka, Redis config
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

### `tracking_events` *(planned, Stage 2)*

Append-only history. `event_id` carries a unique constraint — the database-level idempotency
guarantee. Every event lands here, including ones too old to change `current_status`.

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

---

## 7. Schema management

Flyway owns the schema; Hibernate runs with `ddl-auto: validate` in both production and tests. A
mismatch between a migration and an entity mapping fails at startup rather than being silently
papered over by `ddl-auto: update`.

Migrations are added incrementally per stage (`V1__create_shipments.sql` now, `tracking_events` in
Stage 2, `notifications` in Stage 3) rather than written as one upfront schema — the same discipline
a real deployment needs, where the previous migration has already run in production.

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
