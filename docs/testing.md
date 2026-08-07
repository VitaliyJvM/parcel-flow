# Testing

> ParcelFlow is an independent portfolio and training project. It is not affiliated with or based on
> proprietary systems from any delivery carriers or ecommerce retailers.

```bash
./gradlew test
```

Requires a running Docker daemon. Integration tests start real PostgreSQL 17, Redpanda and Redis
containers.

**226 tests, all passing** (198 from Stages 1–3, 28 added in Stage 4).

---

## Principles

**No context-loads test.** There is no `@Test void contextLoads() {}`. Every test asserts a
behaviour. The Spring context is exercised as a side effect of tests that assert something real,
which catches wiring breakage anyway.

**The cache is off by default in tests.** `parcelflow.cache.enabled=false` in the test profile, so
most tests need no Redis container — and, more usefully, the whole suite doubles as the standing
proof that the service is correct with the cache disabled. The classes that assert caching behaviour
turn it back on and pay for a container.

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

### `OperationalEndpointsIntegrationTest` — 4 tests at Stage 1, 11 now

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

---

## Stage 3 tests

### `ProcessingErrorClassifierTest` — 13 tests, no Spring

The retry policy, pinned. Each category is tied to a concrete exception and each exception to a
concrete answer about whether re-running it can help. Also covers unwrapping — a
`ListenerExecutionFailedException` must be unwrapped to the failure that describes the problem, or
every failure lands in `UNKNOWN` and the policy collapses into "never retry" — and a
self-referential cause chain, which terminates rather than looping.

One test asserts that every exception in the Kafka not-retryable list classifies as
non-auto-retryable, so the error handler's skip list and the recorded category cannot drift apart.

### `IdempotencyAndOrderingIntegrationTest` — 11 tests, real PostgreSQL

| Group | Assertion |
|---|---|
| duplicates | the same event delivered three times stores one row and applies once |
| duplicates | a redelivery does not bump `@Version`, so it cannot lose a concurrent update |
| duplicates | a redelivery creates no second notification |
| duplicates | the duplicate result reports the *stored* outcome and the *current* shipment status |
| duplicates | two different event ids describing the same scan are **not** duplicates — that is a correction, and last-received-wins applies |
| ordering | seq 40 arriving after seq 50 is stored `SUPERSEDED`; status, `lastEventTime` and `lastSequenceNumber` all stay put |
| ordering | a stale milestone creates no notification |
| ordering | same sequence with an older event time does not advance |
| ordering | a newer sequence wins over an older carrier timestamp |
| ordering | a whole journey delivered backwards ends correct, with exactly one applied event and one notification |
| ordering | a delivered parcel ignores a late in-transit scan but still records it |

### `ConcurrentProcessingIntegrationTest` — 3 tests, real PostgreSQL

Drives the processor from eight threads released together by a `CyclicBarrier` — deterministic
overlap, no sleeps. It has to bypass Kafka: partition keying means one parcel's events reach one
consumer thread, so the broker *cannot* produce this situation. The real-world analogue is two
instances mid-rebalance.

- Eight distinct events all land, the highest sequence wins, and the shipment version equals the
  number of applied events — no lost updates.
- The same event from eight threads yields one stored row, one notification, and version 1. This is
  the race the unique constraint exists for.
- No thread exceeds the configured retry ceiling. The first two tests would pass with an unbounded
  loop; this is what catches one.

### `DeadLetterIntegrationTest` — 4 tests, real Redpanda + PostgreSQL

- An unmappable carrier code is recorded with category, origin topic/partition/offset, exception type
  and message — and **no stack trace** — then published to the DLT with the right headers.
- A validation failure is classified separately, because the two need different fixes.
- A poison record does not stall the partition; a later valid record still lands.
- An event for an unknown shipment is classified `SHIPMENT_NOT_FOUND` and stays manually retryable,
  which is what makes the admin endpoint useful once the parcel is registered.

The DLT assertions read the exception *cause* header rather than the exception header: Spring records
the listener wrapper in the latter. They also poll on the test thread rather than through Awaitility,
because `KafkaConsumer` is explicitly not thread-safe.

### `FailedEventApiIntegrationTest` — 10 tests

| Case | Expected |
|---|---|
| listing | paginates, and exposes neither a stack trace nor the payload |
| listing | filters by status |
| unknown id | `404` problem+json |
| retry a `VALIDATION` failure | `409` naming the category; the record is untouched, not consumed |
| retry once the shipment exists | `200`, `RESOLVED`, event applied, shipment advanced |
| retry an already-processed event | `200` reporting `duplicate: true` — still one history row |
| a second concurrent retry | `409`; the claim is a genuine compare-and-set |
| a retry that fails again | `FAILED`, retry count incremented, `firstFailedAt` preserved |
| unparseable payload | `409`, and the record is released rather than stuck in `RETRYING` |
| repeated failures | upsert onto one row rather than piling up |

### `NotificationIntegrationTest` — 16 tests

Parameterized over all five notifiable milestones and the three deliberately silent statuses. Also:
a full journey produces exactly three notifications; the unique constraint rejects a second
notification for the same `(shipment, source event)` even when the service check is bypassed; and the
notifiable set is asserted to be exactly five, so adding one — which means starting to message
customers — cannot happen as a side effect of an unrelated edit.

### `ShipmentCacheIntegrationTest` — 6 tests, real Redis

Hit, miss, invalidation after an applied event, no invalidation after a superseded one, 404s not
cached, and a full round-trip of every field including `Instant` and `LocalDate`.

Assertions go through the `CacheManager` and a `StringRedisTemplate`, not through response timing —
"this was a cache hit" measured by latency is a coin flip on a loaded machine. Presence assertions use
a bounded Awaitility wait, because the cache write is a separate round trip and the framework does not
promise it is visible the instant the read returns.

### `ShipmentCacheUnavailableIntegrationTest` — 3 tests

The service with Redis pointed at a dead port — indistinguishable from an outage, and it does not
disturb the shared container the way stopping it mid-suite would. Reads still return the right
answer, a tracking event still commits when the eviction cannot reach Redis, and a whole journey
processes normally. This is the test behind the claim that correctness does not depend on Redis.

### Simulator tests — 26 tests, no Spring

Every scenario's output shape, plus reproducibility: the same seed produces byte-identical events for
all six scenarios, and different seeds produce different event ids. `DUPLICATE` is asserted to reuse
the *same* `eventId` — a fresh id would be a different event describing the same scan, which is a
different problem. `OUT_OF_ORDER` keeps the endpoints in place so the parcel must still finish
`DELIVERED`.

---

## Coverage against the Stage 3 brief

| Required test | Status |
|---|---|
| Duplicate event delivery | ✅ `IdempotencyAndOrderingIntegrationTest`, `ConcurrentProcessingIntegrationTest` |
| Duplicate notification prevention | ✅ `IdempotencyAndOrderingIntegrationTest`, `NotificationIntegrationTest` |
| Out-of-order history preservation | ✅ `IdempotencyAndOrderingIntegrationTest` (6 cases) |
| Current status protection | ✅ same |
| Optimistic-lock handling | ✅ `ConcurrentProcessingIntegrationTest` |
| Retryable / non-retryable classification | ✅ `ProcessingErrorClassifierTest` |
| Bounded Kafka retry | ✅ `DeadLetterIntegrationTest`; the bound itself in `ConcurrentProcessingIntegrationTest` |
| DLT publishing | ✅ `DeadLetterIntegrationTest` |
| Failed-event persistence | ✅ `DeadLetterIntegrationTest`, `FailedEventApiIntegrationTest` |
| Manual failed-event retry | ✅ `FailedEventApiIntegrationTest` |
| Redis cache hit and miss | ✅ `ShipmentCacheIntegrationTest` |
| Cache invalidation | ✅ same |
| Redis failure tolerance | ✅ `ShipmentCacheUnavailableIntegrationTest` |
| Concurrent processing | ✅ `ConcurrentProcessingIntegrationTest` |
| Simulator scenarios | ◐ generation and reproducibility unit-tested for all six; end-to-end through Kafka verified manually, not automated |


---

## Stage 4 tests

### `TrackingEventMetricsTest` — 9 tests, no Spring

The meter contract, against a `SimpleMeterRegistry`. Metric names are an interface: a dashboard
panel, an alert rule and a runbook all name them as strings, and none of those break at compile time,
so a rename that looks like a harmless tidy-up silently blanks a panel and disarms an alert. These
tests spell the names out so the rename has to be deliberate.

Also asserts the two properties that are easy to get wrong and invisible when wrong:

* **Every meter is registered before anything is processed**, including one failure series per error
  category. A `rate()` over a series that does not exist yet returns no data, which on a dashboard is
  indistinguishable from a broken query.
* **No meter carries an unbounded tag** — not `shipmentId`, `eventId`, `trackingNumber` or
  `correlationId`. Walks every registered meter and checks the tag keys, so a future meter cannot
  quietly introduce one.

And the counting model: `processed = applied + out_of_order + duplicate`, a duplicate is not a
failure, and a failure is not processed.

### `EventProcessingMetricsIntegrationTest` — 7 tests, real PostgreSQL

The same meters, driven by the real pipeline, plus the thing a unit test cannot check: what
`/actuator/prometheus` actually exposes. Micrometer rewrites dots to underscores and appends `_total`
to counters and `_seconds` to timers, so the string a Grafana panel needs is never the string the
Java code declares. Asserting the exported text is the only way to know the two agree — and it is
what caught `parcelflow.notifications.created` being exposed as `parcelflow_notifications_total`,
because `_created` is a reserved OpenMetrics suffix that the client strips.

Also asserts the histogram buckets exist (without them `histogram_quantile()` has nothing to work
with and every latency panel is empty), that the common tags are applied, and that no shipment id
appears anywhere in the scrape output.

Deltas rather than absolute values throughout: the meter registry belongs to a Spring context the
test framework caches and shares, so a counter's absolute value depends on what ran before it.

### `CacheMetricsIntegrationTest` — 2 tests, real Redis

Hits, misses and puts against a real cache. These are Micrometer's built-in meters rather than
counters this project declares, which is the point: they report zero forever unless
`RedisCacheManager` is built with `enableStatistics()`. A dashboard showing a cache with no traffic
looks like a quiet system rather than a broken instrument, and nothing else would catch it.

The assertions check direction, not exact counts — one `@Cacheable` invocation does not map to
exactly one Redis `GET`, and pinning the number would be asserting a Spring implementation detail.

### `CarrierEventLoadControllerIntegrationTest` — 3 tests, real Redpanda + PostgreSQL

The load-testing publish endpoint, enabled by a test property. Worth testing because the k6 scenario
depends on it end to end: a performance number produced by an unverified harness is worse than no
number, and if this endpoint dropped records or keyed them wrongly the load test would still report a
throughput. Asserts that a posted event reaches PostgreSQL through the real broker and consumer, that
a batch publishes every element, and that an invalid payload is forwarded unchanged so the dead
letter path can be load tested.

### `OperationalEndpointsIntegrationTest` — extended from 4 to 11 tests, full stack

Now runs against PostgreSQL, Redpanda **and** Redis, because the readiness policy is a claim about
all three and can only be demonstrated where all three exist. Added:

* Readiness contains `db` and `kafka` and **not** `redis` — with Redis still visible in
  `/actuator/health`, so the outage would not be invisible.
* Liveness contains no dependency at all: a restart does not fix a database that is down, and putting
  a dependency in liveness turns an infrastructure blip into a restart storm.
* The Kafka contributor reports the cluster it reached.
* `env`, `configprops`, `beans`, `loggers`, `threaddump`, `heapdump`, `mappings` and `shutdown` all
  return 404. The exposure allowlist is a security decision, so it gets a test.
* A caller's `X-Correlation-Id` is propagated and echoed back, including on an error response.
* A request without one is given one.
* The load-testing endpoint is **not** exposed by default.

---

## Stage 4 quality gates

### Running the checks locally

```bash
./gradlew test                       # unit + integration, needs Docker
./gradlew checkstyleMain checkstyleTest
./gradlew jacocoTestReport           # build/reports/jacoco/test/html/index.html per module
./gradlew build                      # compile + checkstyle + tests + jar, all of the above
./gradlew dependencyCheckAnalyze     # OWASP; slow without an NVD_API_KEY
./gradlew sonar                      # needs a SonarQube server; see below
```

### What blocks CI

| Check | Blocking | Why |
|---|---|---|
| Compilation | **yes** | |
| Checkstyle | **yes** | The ruleset is small enough that enforcing it is reasonable |
| Unit and integration tests | **yes** | |
| Jar and Docker image build | **yes** | The image builds from a different context and JDK image than the Gradle build, so it can break independently |
| Compose config validation | **yes** | Cheap, and catches a broken demo before someone finds it live |
| k6 script parse (`k6 inspect`) | **yes** | A syntax error should fail in seconds, not three minutes into a load test |
| OWASP Dependency-Check | no — advisory | See below |
| SonarQube | no — advisory, and skipped without a token | See below |

Nothing that verifies correctness is advisory. `continue-on-error` appears exactly twice, on the two
jobs above, and each carries a comment in `.github/workflows/ci.yml` explaining why.

**Why dependency scanning is advisory.** The NVD feed reports CVEs in transitive dependencies that a
Spring Boot version bump resolves on Boot's own schedule. Blocking on those would mean either pinning
versions Boot has not adopted, or adding a suppression to get a green tick — and a suppression added
under time pressure is never revisited. Dependency-Check also produces false positives on shaded jars
at a rate that would train everyone to ignore the job. The report is published as a build artifact
and is meant to be read. For a system handling real customer data the right answer is a blocking scan
with a triaged suppression file and an owner.

**Why Sonar is advisory.** It is skipped entirely unless a `SONAR_TOKEN` secret exists, so a fork does
not get a permanently red job for a server it cannot reach. Quality-gate thresholds nobody has tuned
are not a merge gate.

### Checkstyle: what is enforced, and what is not

Deliberately small. Every rule is either something a reviewer would otherwise have to say out loud,
or something with a real chance of being a bug: star and unused imports, import order, naming, empty
statements, `equals`/`hashCode` pairing, string comparison with `==`, missing `@Override`, missing
braces, `catch (Throwable)`, utility-class constructors, line length 120, trailing whitespace.

Left out on purpose: brace placement, blank-line counts, mandatory Javadoc, whitespace-around
operators. Those generate hundreds of diffs that bury functional history, and a formatting sweep that
large makes every subsequent `git blame` useless. Applying Stage 4's ruleset required **four** source
changes across the existing codebase — three import reorderings and one wrapped line — which is the
budget a style gate should cost when it is introduced late.

Because the ruleset is small, violations fail the build (`maxWarnings = 0`). A style check that only
warns is a style check nobody reads.

**Accepted exclusions** (`config/checkstyle/suppressions.xml`, each with its reason in the file):

* `CustomImportOrder` off for test sources. Test classes import assertion and request-builder statics
  in bulk, which is the idiom those libraries are designed around.
* `HideUtilityClassConstructor` off for the two Spring Boot entry-point classes. They look like
  utility classes but are also configuration classes Spring instantiates; the private constructor the
  rule asks for would stop the application from starting.
* `ConstantName` accepts `log` alongside `SCREAMING_SNAKE`. A static final `Logger` is a constant by
  the letter of the rule and a collaborator by every other measure. Renaming sixteen loggers to `LOG`
  is exactly the churn this ruleset exists to avoid.

`config/owasp/dependency-check-suppressions.xml` is empty, and the file explains what an entry has to
say before it goes in.

### Running SonarQube Community Build locally

```bash
docker run -d --name sonarqube -p 9000:9000 sonarqube:community
# first login admin/admin, change the password, then create a token
SONAR_TOKEN=<token> SONAR_HOST_URL=http://localhost:9000 ./gradlew test jacocoTestReport sonar
```

Coverage first: Sonar reads the JaCoCo XML, and without a preceding test run the dashboard reports
0%, which is worse than reporting nothing. SonarQube is not part of `check` and is not required to
build the project.

### Coverage

JaCoCo runs with the tests and reports per module. There is no coverage threshold, deliberately: a
percentage gate on a codebase where the valuable tests are integration tests measures how much
framework code got executed. The report is for reading, and the question worth asking of it is which
*branches* are untested, not what the number is.

---

## Coverage against the Stage 4 brief

| Required | Status |
|---|---|
| Custom meters registered and incremented | ✅ `TrackingEventMetricsTest`, `EventProcessingMetricsIntegrationTest` |
| Exported Prometheus names match what dashboards query | ✅ `EventProcessingMetricsIntegrationTest` |
| Metric label cardinality bounded | ✅ asserted in both, against the registry and the scrape output |
| Cache metrics | ✅ `CacheMetricsIntegrationTest` |
| Backlog gauges | ✅ `EventProcessingMetricsIntegrationTest` |
| Notification metrics | ✅ `EventProcessingMetricsIntegrationTest` |
| Readiness reflects the documented policy | ✅ `OperationalEndpointsIntegrationTest` |
| Liveness independent of dependencies | ✅ same |
| Sensitive actuator endpoints not exposed | ✅ same |
| Correlation id propagated and generated | ✅ same |
| Load-test endpoint disabled by default | ✅ same |
| Load-test endpoint works when enabled | ✅ `CarrierEventLoadControllerIntegrationTest` |
| Structured JSON log fields | ◐ verified by hand against the running stack (see [operations.md](operations.md#5-structured-logging)); not asserted by a test, because asserting on an appender's output tests Boot's formatter rather than this code |
| Resilience experiments | ◐ documented and reproducible in [operations.md](operations.md#6-resilience-verification); run by hand, not automated |
| Load test | ◐ the harness is tested; the run itself is manual, with results in [performance-results.md](performance-results.md) |
