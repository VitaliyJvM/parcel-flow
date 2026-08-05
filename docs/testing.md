# Testing

> ParcelFlow is an independent portfolio and training project. It is not affiliated with or based on
> proprietary systems from any delivery carriers or ecommerce retailers.

```bash
./gradlew test
```

Requires a running Docker daemon. Integration tests start a real PostgreSQL 17 container.

**Stage 1: 35 tests, all passing.**

---

## Principles

**No context-loads test.** There is no `@Test void contextLoads() {}`. Every test asserts a
behaviour. The Spring context is exercised as a side effect of tests that assert something real,
which catches wiring breakage anyway.

**No mocked database.** The unique constraint, the `@Version` increment, and `ddl-auto: validate`
against the Flyway schema cannot be verified against an in-memory database or a mock repository.
Those are precisely the behaviours the system depends on, so the tests use the real engine.

**One container per JVM.** `PostgresIntegrationTest` starts the container from a static initializer
rather than through the `@Testcontainers` extension. The extension scopes a static `@Container` to a
single test class, so every integration test class would pay a fresh container start. Starting it
once per JVM means all classes share one, and Spring's context cache keeps the application context
warm alongside it. Full suite runtime is roughly 20 seconds.

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

---

## Coverage against the brief

| Required test | Status |
|---|---|
| Shipment status transition | ✅ `ShipmentTest` |
| Out-of-order events | ✅ `ShipmentTest` (5 cases) |
| PostgreSQL integration | ✅ `ShipmentPersistenceIntegrationTest` |
| REST API | ✅ `ShipmentApiIntegrationTest` |
| Optimistic locking | ◐ `@Version` increment verified; the concurrent-conflict test lands in Stage 3 with the retry policy |
| Carrier event normalization | Stage 2 |
| Duplicate events | Stage 3 |
| Invalid messages | Stage 3 |
| Dead Letter Queue | Stage 3 |
| Kafka integration (Testcontainers) | Stage 2 |
| Redis integration | Stage 3 |

---

## Configuration

`src/test/resources/application-test.yml` keeps `ddl-auto: validate` in tests deliberately. Letting
Hibernate create the schema in tests would mean the tests validate the entities against themselves
and never against the migrations that actually run in production.
