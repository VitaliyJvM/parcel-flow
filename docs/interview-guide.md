# Interview guide

Preparation notes for talking about ParcelFlow. Everything here is checkable against the code; where
something is a limitation, it says so, because a claim that collapses under one follow-up question
is worse than no claim.

---

## 1. The two-minute explanation

> ParcelFlow is an independent portfolio project — a shipment tracking event processor. The business
> scenario is an ecommerce retailer that ships through several carriers and wants one consistent
> view of where every parcel is. Each carrier publishes its own event vocabulary on its own
> schedule, and the system has to turn that into one status per parcel plus a full history, and
> notify the customer at the milestones that matter.
>
> Architecturally it is a modular monolith: one Spring Boot service with enforced module boundaries
> — shipment, tracking, carrier, notification — consuming from Kafka, with PostgreSQL as the source
> of truth and Redis as an optional read cache. One service rather than several because the event
> consumer updates the shipment, appends to history and creates the notification in a single local
> transaction. Splitting those across processes would add a distributed transaction to a problem
> that does not have one.
>
> The interesting part is not the CRUD; it is that the event stream misbehaves. Kafka is
> at-least-once, so redelivery is normal, not exceptional. Carriers backfill scans, so events arrive
> out of order. Payloads are sometimes malformed. Two threads can touch the same parcel at once. The
> **main reliability challenge** was making processing idempotent in a way that survives the crash
> that causes duplicates in the first place — which rules out an in-memory set and rules out a Redis
> check, because neither is atomic with the database write. The answer is a unique constraint on
> `event_id`, with a pre-check for the common case, and treating a duplicate as a success rather
> than a failure.
>
> The **key trade-off** is at-least-once over at-most-once. Offsets commit after the database
> transaction, so a crash in that gap causes redelivery. That is deliberate: a duplicate is
> recoverable, a lost parcel scan is not.
>
> **Testing** is 226 tests with no mocked infrastructure — Testcontainers starts real PostgreSQL,
> Redpanda and Redis, so the migrations, the constraints and the consumer semantics are all under
> test. The idempotency and ordering claims are tests, not comments.
>
> **What I learned** was mostly about where correctness actually lives. Every guarantee that
> survived scrutiny turned out to be a database constraint, and every guarantee that turned out to
> be decorative was application logic that a race could walk past. The other thing I learned is that
> transaction boundaries are the design: duplicate detection, retry, failed-event persistence and
> cache eviction each have to sit outside the processing transaction, and each of them is a bug
> inside it.

## 2. Architecture walkthrough

**API layer.** Spring MVC, three read endpoints and one write, plus an admin subtree. Errors are RFC
9457 `application/problem+json` with a stable `type` URI so clients branch on the error kind rather
than parsing prose. Duplicate registration relies on the `(carrier_code, tracking_number)` unique
constraint rather than a `SELECT`-then-`INSERT`, which two concurrent requests could both pass.
`/api/admin/**` is unauthenticated in this build — a known gap, with the path prefix chosen so the
whole subtree can be secured by one rule.

**Event ingestion.** A `@KafkaListener` on `carrier-tracking-events`, three threads, one per
partition. Every event is keyed by shipment id, so one parcel's scans land on one partition and
reach one consumer in production order — ordering is guaranteed per parcel, never globally, which is
exactly the guarantee the domain needs. The deserializer is pinned to one class and ignores producer
type headers: a producer does not get to choose which class the consumer instantiates. The listener
is transport only — it sets up the logging context and delegates.

**Normalization.** Each carrier's vocabulary is normalized by its own strategy bean, discovered
through a registry, so adding a carrier is adding a bean. It is not a string transform:
`DELIVERY_FAILED` maps to `DELIVERY_ATTEMPTED` and `COMPLETE` maps to `DELIVERED`. An unmapped code
is a typed exception the error classifier recognises as permanently invalid.

**Persistence.** PostgreSQL, schema owned by Flyway, Hibernate on `ddl-auto: validate` so a drift
between an entity and a migration breaks the build. The history insert and the shipment update are
one transaction, so a parcel can never show a status that no stored event justifies.

**Idempotency.** A pre-check on `event_id` for the common redelivery, and a `UNIQUE (event_id)`
constraint for the race the pre-check cannot cover. When the constraint fires, the orchestrator
turns it into a successful no-op — outside the failed transaction, because both the constraint
violation and a lock conflict leave the transaction rollback-only.

**Event ordering.** The carrier's per-shipment `sequenceNumber` is the ordering authority, with
`eventTime` as tie-break. Statuses are deliberately unranked — `DELAYED` and `DELIVERY_ATTEMPTED`
are exception states that occur at several points, `ARRIVED_AT_FACILITY` repeats — so a rank would
encode a false model of a carrier network. A late event is still appended to history, marked
`SUPERSEDED`, because it is a real observation. `DELIVERED` is terminal and sticky, so a backfilled
scan cannot un-deliver a parcel.

**Concurrency.** JPA `@Version` optimistic locking on the shipment row. A conflict is retried a
bounded number of times, each in a fresh transaction, which re-reads and re-runs the ordering
decision against the state that actually won. Bounded because an unbounded retry under contention is
a livelock that consumes a consumer thread.

**Notifications.** A rule engine mapping normalized statuses to customer-facing milestones, running
inside the event transaction so a notification and the event that justifies it commit together. Only
applied events notify — telling a customer their delivered parcel is in transit is worse than saying
nothing — and a unique constraint on `(shipment_id, source_event_id)` is what makes that true under
a race. Nothing dispatches; the records are the deliverable.

**Caching.** Redis caches the tracking response, with a TTL as a backstop. Eviction fires
`AFTER_COMMIT` — inside the transaction it would let a concurrent reader repopulate from pre-commit
state and pin a wrong value until the TTL expired. Every cache operation is best-effort: the error
handler swallows and logs, so an unavailable Redis degrades latency and nothing else. Redis is
excluded from readiness for the same reason.

**Observability.** Micrometer to Prometheus, with counters for every processing outcome, a
histogram for processing duration, and gauges for backlog. No identifier is ever a metric label —
that is how a Prometheus server runs out of memory — so identifiers live in the logs instead, which
are ECS JSON carrying `correlationId`, `eventId`, `shipmentId`, `carrierCode`, `topic`, `partition`,
`offset`, `traceId` and `spanId`. Liveness contains nothing a restart cannot fix; readiness contains
PostgreSQL and Kafka and deliberately not Redis. Grafana and the alert rules are provisioned from
files in the repository.

---

## 3. Distributed systems questions

### 3.1 Why is Kafka delivery not exactly once, from the application's point of view?

Because there are two systems and one of them has to commit first. This service processes a record,
commits a PostgreSQL transaction, and then the container commits the offset. There is no atomic
commit across both, so there is a window where the work is durable and the offset is not — and a
crash in that window means the record is redelivered.

Kafka's "exactly once" is transactional writes *within* Kafka: consume-transform-produce with the
offset commit in the same Kafka transaction. It does not extend to a side effect in an external
database. It would be possible to move the offsets into PostgreSQL and commit them with the work —
that genuinely gets you effectively-once — but you give up the consumer group's offset management
and take on the bookkeeping yourself.

ParcelFlow chose the other direction: accept at-least-once and make reprocessing a no-op. The
duplicate path is cheap, the failure mode is a redundant read, and the property it buys — no lost
scan — is the one the domain cares about.

### 3.2 How does the system prevent duplicate processing?

Two mechanisms, and only one of them is a guarantee.

The pre-check — look up `event_id`, return early if it exists — handles the overwhelmingly common
case cheaply, avoiding a rolled-back transaction for an expected condition.

The guarantee is `UNIQUE (event_id)` on `tracking_events`. Two threads can both pass the pre-check;
one insert wins and the other gets a constraint violation, which the orchestrator catches *outside*
the transaction and turns into a duplicate result. A second constraint on
`(shipment_id, source_event_id)` does the same job for notifications.

What was rejected, and why:

* **An in-memory seen-set** dies in exactly the crash that causes the duplicates.
* **A Redis check** has no atomicity with the database write: the window between "not in Redis" and
  "committed to PostgreSQL" is where the duplicate gets in, and Redis is allowed to be unavailable.
* **Kafka's exactly-once semantics** would not cover the database write, as above.

The general principle: idempotency has to be enforced by the same thing that stores the result, or
it is not enforced.

### 3.3 How are out-of-order events handled?

Ordering authority is the carrier's per-shipment `sequenceNumber`, with `eventTime` as the tie-break
when a carrier omits it. On each event the shipment aggregate compares the incoming ordering key
against its own last one and decides whether to advance.

Three things about the decision are deliberate:

* **A late event is still stored**, marked `SUPERSEDED`. It is a real observation, and dropping it
  would make history a summary instead of a record.
* **Statuses are not ranked**, because a parcel's journey is not linear. An integer rank would say
  `ARRIVED_AT_FACILITY` cannot follow `OUT_FOR_DELIVERY`, which it does, all the time.
* **`DELIVERED` is terminal**, so a backfilled scan with a higher sequence number cannot un-deliver a
  parcel.

Per-partition ordering does most of the work in practice: keying by shipment id means one parcel's
events reach one consumer in production order. The ordering logic is for when the *carrier* sends
them out of order, which is a different problem from Kafka reordering them and is the one that
actually happens.

Limitation, stated plainly: `sequenceNumber` is trusted. The `eventTime` fallback handles a carrier
omitting it, not a carrier resetting it mid-journey.

### 3.4 Why is PostgreSQL the source of truth rather than Redis?

Because the writes need transactions and constraints, and Redis offers neither in the form this
needs.

One event produces three writes — a history row, a shipment update, a notification — that must
commit together or not at all. In PostgreSQL that is one local transaction. In Redis it would be
three commands with no rollback, so a crash between them leaves a parcel showing a status no stored
event justifies.

Then there is durability: Redis's whole configuration here is `--save "" --appendonly no`, because
everything in it is a derived copy of a row. And the idempotency guarantee is a unique constraint,
which is a database feature.

Redis's job is exactly one thing: making a repeated read of a hot tracking page cheaper. Every value
in it is derived, has a TTL, and can be thrown away at any moment. The proof is operational — the
whole test suite runs with the cache disabled, and stopping Redis in the running stack changes
latency and nothing else.

### 3.5 What happens when the consumer crashes after the database commit but before the Kafka acknowledgement?

The record is redelivered when the consumer restarts or a rebalance reassigns the partition, because
the offset was never committed. Processing runs again, the recorder's pre-check finds the `event_id`
already in `tracking_events`, and it returns a duplicate result: no shipment update, no notification,
nothing changed. The offset commits this time.

Observable effect: `parcelflow_tracking_events_duplicate_total` increments and one INFO line is
logged. No error, no alert.

This is not a hypothetical — it is
[experiment 1 in the operations guide](operations.md#experiment-1--restart-the-service-during-event-production), which restarts the service mid-stream and checks that the stored history contains
no duplicate `event_id`.

The reason this window exists at all is the choice to commit the offset after the work rather than
before. The other order would close this window and open a much worse one: a crash after the offset
commit and before the database commit loses the scan entirely, with nothing to detect it.

### 3.6 How does optimistic locking help?

The shipment row carries a `@Version`. Two consumer threads processing events for the same parcel
both read version 5, both decide, and both write — the second update matches zero rows and Spring
raises `OptimisticLockingFailureException`. Without it, the second write would silently overwrite
the first, and a parcel would end up with a status derived from an event that was never the latest.

The retry is what makes it useful, and it has to be a *fresh transaction*: the ordering decision is a
pure function of (current state, incoming event), so re-reading the shipment and re-running the
decision against the state that actually won produces the right answer. Re-running inside the
poisoned persistence context would not.

Optimistic rather than pessimistic because conflicts are rare — one parcel's events share a
partition key and therefore one thread — and `SELECT FOR UPDATE` on every event would serialize a
workload that mostly does not conflict. Retries are bounded; exhausting them hands the record back
to the Kafka error handler, whose backoff spaces out the next attempt rather than hammering the row.

### 3.7 What messages are retryable?

`ErrorCategory` answers two independent questions per failure, because they have different answers:
*will re-running the same bytes seconds later plausibly succeed* (automatic retry), and *could it
succeed after a human or a deployment changes something* (manual retry).

| Category | Auto | Manual | Reasoning |
|---|---|---|---|
| `MALFORMED_PAYLOAD` | no | no | The bytes will not become parseable. |
| `VALIDATION` | no | no | A missing field stays missing. |
| `UNKNOWN_EVENT_TYPE` | no | no | Republish after adding the mapping. |
| `CARRIER_MISMATCH` | no | no | A data problem at the source. |
| `UNSUPPORTED_CARRIER` | no | **yes** | No amount of waiting adds a bean; a deployment does. |
| `SHIPMENT_NOT_FOUND` | **yes** | yes | A first scan can genuinely beat the registration call. |
| `CONCURRENCY_CONFLICT` | **yes** | yes | Re-reading is the fix. |
| `INFRASTRUCTURE` | **yes** | yes | Transient by definition. |
| `UNKNOWN` | no | yes | As likely a permanent bug as a blip; an operator decides better than a backoff policy. |

`SHIPMENT_NOT_FOUND` being retryable is the interesting one: the cost of being wrong is a second of
backoff, and the cost of the opposite default is losing the first scan of a real parcel.

One classifier feeds the Kafka error handler's not-retryable list, the stored `failed_events` row and
the manual retry endpoint, so the three cannot drift into disagreeing.

### 3.8 How would the system scale?

**Reads** are the easy direction: the service is stateless, so more instances behind a load balancer,
with Redis absorbing repeated reads of hot parcels and a read replica if the history query becomes
the constraint.

**Ingest** scales by partitions. Events are keyed by shipment id, so partitions can be added and
consumers added up to the partition count without breaking per-parcel ordering. The current setup is
three partitions and one instance with three threads. The next step is more partitions than
instances, and instances that can come and go — which a rebalance makes safe *because* processing is
idempotent. Adding partitions to an existing topic changes the key-to-partition mapping, so events
for a parcel mid-journey can move to a different partition; the ordering rules handle the resulting
reordering, which is precisely why they exist.

**Writes** are the real ceiling: one transaction per applied event. The first move would be batching
within a partition — one transaction for several events for the same parcel — rather than a bigger
database. Partitioning `tracking_events` by time would keep the hot set small, since nobody queries
last year's scans.

**What would not scale by adding instances** is anything that assumed a single process. There is
nothing like that here, which is the point of putting the idempotency guarantee in the database.

### 3.9 What are the current bottlenecks?

From the [measured run](performance-results.md): 194 events/s published, 197/s processed, zero
consumer lag, p99 event processing 10 ms, p99 API latency 25 ms, 181 MB peak heap. **That run did not
find a bottleneck — it found the rate the load generator was configured for.** The honest answer
starts there.

In the order they would break, reasoning from the design:

1. **One consumer instance, three threads, three partitions.** A fourth thread would idle.
2. **One transaction per applied event** — insert plus update — which becomes PostgreSQL write
   throughput and fsync latency.
3. **Optimistic-lock contention on hot parcels.** Zero conflicts at 200 parcels; a workload
   concentrated on a few very active parcels would produce retries, and each retry redoes a whole
   transaction.
4. **The Hikari pool of 16**, shared between consumer threads and request threads.
5. **Cache hit ratio under write-heavy load** — 45% in the measured run, because ingest was evicting
   the same parcels the readers were reading. Correct behaviour, low value; that workload is close to
   the worst case for this cache.

And the measurement's own limits: everything ran on one 8-core laptop including the load generator,
the dataset fitted in page cache, and the run was two minutes — long enough for a throughput number,
far too short for connection leaks or index bloat.

### 3.10 What would you change for a real multi-region production system?

Multi-region is mostly a question about where the source of truth lives, and the honest answer is
that ParcelFlow's current design assumes one.

**Data.** One primary region for writes, read replicas elsewhere. Parcel tracking tolerates read
staleness well — a customer seeing a scan a second late is fine — so async replication is
acceptable, but the write path must stay in one place or `UNIQUE (event_id)` stops being a global
guarantee. Multi-primary would mean giving up that constraint and moving idempotency to a
deterministic id with conflict-free merge semantics, which is a much larger redesign than it sounds.

**Messaging.** Regional clusters with MirrorMaker, and events routed to the region that owns the
parcel. Consuming the same partition from two regions breaks per-parcel ordering.

**Correctness.** Everything the current design relies on — one local transaction, one unique
constraint, one optimistic-locked row — is single-region reasoning. Crossing regions means either
keeping the write path pinned, or accepting eventual consistency per parcel and making the ordering
rules the merge function. The current rules were built to be exactly that kind of pure function of
(state, event), so the road exists.

**Operations.** Alertmanager and an on-call rotation. A trace backend with sampling well under 100%.
Log shipping with retention. Management endpoints on a port that is not publicly routed. Secrets
from a secret manager, not a Compose file. Authentication in front of `/api/admin/**`. A schema
registry, so the event contract stops being two hand-synchronised copies.

**And the things that would go first regardless of regions:** a multi-instance rebalance test,
because the safety argument is currently argued rather than demonstrated; a bulk DLT replay, because
one-at-a-time retry does not survive an incident that dead-letters ten thousand events; and
dispatching the notifications, which today are records nothing moves out of `PENDING`.

---

## 4. Honest limitations, in one place

Worth being able to list these without prompting — being asked for a limitation and having none is a
worse answer than any of them.

* One consumer instance; the rebalance-safety argument is not demonstrated by a test.
* `/api/admin/**` is unauthenticated.
* The event contract is declared twice, once per module, and kept in step by hand.
* No bulk DLT replay.
* `sequenceNumber` is trusted.
* Notifications are never dispatched.
* Spans are created and dropped — trace context propagation, no trace backend.
* The load test found the configured rate, not the system's ceiling.
* Alert thresholds are starting points, not values derived from an error budget.
* Actuator shares the API port with health details on.
* The load-testing publish endpoint exists and must stay disabled outside a load test.

---

## 5. Portfolio and resume material

### Resume bullets

Accurate as of the [measured run recorded on 2026-08-06](performance-results.md); nothing below
claims production deployment or traffic.

* Built **ParcelFlow**, an independent portfolio project processing multi-carrier shipment tracking
  events in Java 25 / Spring Boot 4 with Kafka, PostgreSQL and Redis; designed idempotent
  at-least-once consumption using a database uniqueness guarantee plus optimistic locking, verified
  under a mixed load of 23,990 events with 2,161 duplicates and 2,032 out-of-order events processed
  with zero data loss and zero incorrect status transitions.

* Designed the event-ordering and failure-handling model — per-shipment sequence authority, terminal
  status protection, a nine-category error classifier driving retry, dead-letter and operator-retry
  policy from one declaration — and proved it with 226 tests that run against real PostgreSQL, Kafka
  and Redis containers via Testcontainers rather than mocks.

* Instrumented the service end to end with Micrometer, Prometheus, Grafana and ECS-structured JSON
  logging: custom throughput, latency, duplicate, out-of-order, dead-letter and backlog metrics with
  deliberately bounded label cardinality, ten alert rules, correlation-id and W3C trace propagation
  across HTTP and Kafka, plus a k6 load scenario and a GitHub Actions pipeline gating on tests,
  Checkstyle and dependency scanning.

### One-line GitHub description

> Distributed shipment tracking event processor — Java 25, Spring Boot 4, Kafka, PostgreSQL, Redis.
> Idempotent at-least-once processing, out-of-order event handling, dead-letter recovery, and full
> Prometheus/Grafana observability. An independent portfolio project.

### LinkedIn project description

> **ParcelFlow — Distributed Shipment Event Processor** (independent portfolio project)
>
> A shipment tracking system that ingests events from multiple delivery carriers, normalizes their
> differing vocabularies into one status model, maintains each parcel's current state and full
> history, and generates customer notifications for delivery milestones.
>
> The engineering focus is what happens when the event stream misbehaves. Kafka gives at-least-once
> delivery, so redelivery is the normal case rather than an edge case; carriers backfill scans, so
> events arrive out of order; payloads are sometimes malformed; and concurrent updates race on the
> same parcel. ParcelFlow handles each explicitly: idempotency enforced by a database uniqueness
> guarantee rather than application state, ordering decided by the carrier's per-shipment sequence
> with terminal-status protection, optimistic locking with bounded retry, and a classifier that
> decides per failure category whether a message is worth retrying, dead-lettering, or handing to an
> operator.
>
> Built as a modular monolith on purpose: the consumer updates the shipment, appends history and
> creates the notification in one local transaction, so there is no distributed transaction to get
> wrong. Redis is a cache that the system is designed to run without, and the test suite runs with it
> disabled as the proof.
>
> Fully instrumented — Prometheus metrics with deliberately bounded label cardinality, a provisioned
> Grafana dashboard, ten alert rules, ECS-structured JSON logs with correlation and trace ids — and
> load tested with k6 at ~195 events/s alongside concurrent reads on a single laptop, with the
> published and processed event counts reconciled to confirm nothing was silently dropped. 226 tests
> run against real PostgreSQL, Kafka and Redis containers; CI gates on tests, style and dependency
> scanning.
>
> Not affiliated with or based on proprietary systems from any delivery carrier or ecommerce
> retailer. Carrier names in the project are fictional.

### Technology list

Java 25 · Spring Boot 4 · Spring Kafka · Spring Data JPA · Hibernate · PostgreSQL 17 · Flyway ·
Apache Kafka / Redpanda · Redis 7 · Spring Cache · Micrometer · Prometheus · Grafana · Micrometer
Tracing · OpenAPI / springdoc · Docker · Docker Compose · Gradle · JUnit 6 · AssertJ · Awaitility ·
Testcontainers · k6 · GitHub Actions · Checkstyle · JaCoCo · OWASP Dependency-Check · SonarQube

### Five keywords for distributed-systems roles

`event-driven architecture` · `idempotency` · `exactly-once semantics` · `distributed tracing and
observability` · `fault tolerance`
