# Operations

How to run ParcelFlow, what it reports about itself, and what to do when something is wrong.

Sibling documents: [troubleshooting.md](troubleshooting.md) for specific failures,
[performance-results.md](performance-results.md) for measured load behaviour,
[architecture.md](architecture.md) for why the system is shaped this way.

---

## 1. The local stack

```bash
docker compose up --build -d      # everything except the simulator and the load test
docker compose ps                 # postgres, redpanda, redis, tracking-service should be healthy
docker compose logs -f tracking-service
docker compose down               # add -v to drop the database, broker and Grafana volumes
```

| Service | URL | Notes |
|---|---|---|
| API | <http://localhost:8080> | |
| Swagger UI | <http://localhost:8080/swagger-ui.html> | |
| OpenAPI JSON | <http://localhost:8080/v3/api-docs> | |
| Health | <http://localhost:8080/actuator/health> | Everything, including Redis |
| Liveness | <http://localhost:8080/actuator/health/liveness> | |
| Readiness | <http://localhost:8080/actuator/health/readiness> | PostgreSQL and Kafka only |
| Metrics (Prometheus format) | <http://localhost:8080/actuator/prometheus> | |
| Metrics (JSON, browsable) | <http://localhost:8080/actuator/metrics> | |
| **Prometheus** | <http://localhost:9090> | Targets: `/targets`. Alerts: `/alerts` |
| **Grafana** | <http://localhost:3000> | Opens on the ParcelFlow dashboard; no login |
| PostgreSQL | `localhost:5432` | `parcelflow` / `parcelflow` / db `parcelflow` |
| Kafka (Redpanda) | `localhost:19092` | `redpanda:9092` between containers |
| Redpanda admin | <http://localhost:9644> | |
| Redis | `localhost:6379` | |

Grafana is provisioned from `monitoring/grafana/` — the datasource and the dashboard are files in
this repository, not clicks in a UI, so `docker compose down -v` loses nothing.

---

## 2. Metrics catalogue

Every meter this service publishes, with the name a Grafana query or an alert rule has to use.
Micrometer names are dotted; the Prometheus exposition rewrites them. Where the two differ in a way
that is not mechanical, the row says why.

### Event processing

| Prometheus name | Type | Labels | Meaning |
|---|---|---|---|
| `parcelflow_tracking_events_received_total` | counter | — | Events that entered the pipeline. Includes Kafka redeliveries and operator-driven manual retries: both are genuinely another unit of work. Excludes records that failed deserialization, which never reach the processor. |
| `parcelflow_tracking_events_processed_total` | counter | — | Completed without error. Equals applied + out of order + duplicate. |
| `parcelflow_tracking_events_applied_total` | counter | — | Advanced the shipment's current status. |
| `parcelflow_tracking_events_out_of_order_total` | counter | — | Stored in history but too late to change the status. Not an error. |
| `parcelflow_tracking_events_duplicate_total` | counter | — | An event already in history. Not an error. |
| `parcelflow_tracking_events_failed_total` | counter | `category`, `retryable` | One increment per failed attempt. `category` is `ErrorCategory`, assigned by the same classifier that decides the retry policy. |
| `parcelflow_tracking_events_dlt_total` | counter | — | Published to the dead letter topic. |
| `parcelflow_tracking_events_optimistic_lock_conflicts_total` | counter | — | Shipment update conflicts that triggered an in-process retry. |
| `parcelflow_tracking_event_processing_duration_seconds` | histogram | `outcome` | End to end for one event, including in-process retries. `outcome` is `applied`, `out_of_order`, `duplicate` or `failed`. Buckets are published, so use `histogram_quantile()`. |

### Notifications

| Prometheus name | Type | Labels | Meaning |
|---|---|---|---|
| `parcelflow_notifications_total` | counter | `type` | Notification records created, by milestone. |
| `parcelflow_notifications_skipped_total` | counter | `reason` | `not_notifiable` (the status is not a customer-facing milestone — the majority, by design) or `already_exists` (the event was reprocessed). |

> **The created counter is not called what you expect.** Its Micrometer name is
> `parcelflow.notifications.created`, which is what `/actuator/metrics` shows. Prometheus exposes it
> as `parcelflow_notifications_total`, because `_created` is a reserved OpenMetrics suffix — it
> marks a series' creation timestamp — and the client strips it before appending `_total`. There is
> no way to expose `parcelflow_notifications_created_total` from a counter. An integration test
> asserts the exported name so this cannot drift silently.

### Cache

Micrometer's Redis binder, not counters this project declares. They report zero unless
`RedisCacheManager` is built with `enableStatistics()`, which it is.

| Prometheus name | Type | Labels | Meaning |
|---|---|---|---|
| `cache_gets_total` | counter | `cache`, `result` | `result` is `hit`, `miss` or `pending`. |
| `cache_puts_total` | counter | `cache` | Entries written. |
| `cache_removals_total` | counter | `cache` | Explicit evictions — ParcelFlow evicting a shipment after an event advanced it. |
| `cache_lock_duration_seconds` | timer | `cache` | Time spent waiting on Spring Data Redis's cache lock. |
| `parcelflow_cache_failures_total` | counter | `operation`, `cache` | **Ours.** Redis operations that threw and were swallowed. `operation` is `get`, `put`, `evict` or `clear`. |

**There is no `cache_evictions_total`.** Redis exposes no eviction statistic to a client, so the
meter does not exist and the dashboard has no panel for it. Redis-side eviction under `maxmemory`
pressure is visible in `redis-cli INFO stats` (`evicted_keys`), which would need a Redis exporter to
reach Prometheus. `parcelflow_cache_failures_total` is the metric that covers the failure case the
brief cares about, and it exists precisely because the service swallows these errors by design —
without it, a Redis outage shows up only as a latency change and some WARN lines nobody is watching.

### Business and backlog gauges

Refreshed every 15 s by a scheduled task, not computed on scrape: a gauge that runs a `COUNT` on the
scrape thread makes the database load scale with the number of scrapers and turns a slow database
into a hanging metrics endpoint.

| Prometheus name | Meaning |
|---|---|
| `parcelflow_shipments_active` | Shipments not in a terminal status. |
| `parcelflow_failed_events_awaiting_review` | `failed_events` rows in `FAILED`, waiting for an operator. |
| `parcelflow_dead_letters_unresolved` | `failed_events` rows not yet resolved, in any status. Every dead-lettered message has one of these rows. |

Backlog gauges matter because rates heal themselves and backlogs do not: an event that failed
yesterday is still a parcel with a missing scan today, and a failure-rate alert has long since
resolved.

### Inherited meters worth knowing

| Prometheus name | Meaning |
|---|---|
| `http_server_requests_seconds{_count,_sum,_bucket}` | Per `uri`, `method`, `status`, `outcome`. Histogram enabled. |
| `kafka_consumer_fetch_manager_records_lag_max` | Consumer lag, from the Kafka client's own metrics, one series per consumer thread. **Reads `NaN` while the consumer is idle** — the client reports no lag when it has not fetched recently, so the panel shows gaps on a quiet stack. A number appears as soon as records flow. |
| `spring_kafka_listener_seconds` | Per-record listener timing, from the observation the listener container creates. |
| `jvm_memory_used_bytes`, `jvm_gc_*`, `process_cpu_usage`, `system_cpu_usage` | Standard JVM and process meters. |
| `spring_data_repository_invocations_seconds` | Per repository method. |

### Cardinality

No meter is labelled with `shipmentId`, `eventId`, `trackingNumber` or `correlationId`. Those are
unbounded, and an unbounded label is one time series per value — which is how a Prometheus server
runs out of memory. Identifiers belong in logs, where they can be searched. A unit test asserts that
no meter carries one of those tag keys, and an integration test asserts the same against the actual
scrape output.

`http_server_requests` is labelled with `uri`, which is bounded by the number of mapped path
patterns — until an unmapped path or a scanning bot arrives. A `MeterFilter` caps that meter at 200
`uri` values and denies new ones beyond it.

---

## 3. Alerting

Rules live in `monitoring/prometheus/alert-rules.yml` and are evaluated by the local Prometheus.
There is no Alertmanager: firing alerts are visible at <http://localhost:9090/alerts>, which is the
part worth showing. Routing a page to a human is a deployment concern.

| Alert | Fires when | Severity |
|---|---|---|
| `TrackingServiceDown` | The scrape target is down for 1m | critical |
| `EventProcessingFailureRateHigh` | >5% of events failing over 5m | critical |
| `DeadLetterRateElevated` | Any sustained dead-lettering over 10m | warning |
| `EventProcessingLatencyHigh` | p99 event processing >1s for 10m | warning |
| `ConsumerLagGrowing` | Max lag >1000 for 10m | warning |
| `FailedEventsAwaitingReview` | >20 unreviewed failed events for 15m | warning |
| `NoEventsReceived` | No events for 30m | info |
| `ApiErrorRateHigh` | >2% 5xx over 5m | critical |
| `ApiLatencyHigh` | p95 API latency >500ms for 10m | warning |
| `CacheDegraded` | Any swallowed Redis failures over 5m | info |

The thresholds are starting points chosen for a laptop, not values derived from an error budget.
What is worth taking from them is the shape:

* **Symptoms, not resources.** Nothing alerts on CPU. High CPU with healthy latency is not a
  problem, and low CPU with a stalled consumer is.
* **Ratios, not counts.** Five failures a minute is a catastrophe at ten events a minute and noise
  at ten thousand.
* **Rates over windows, with a `for:` clause.** One bad second must not page anyone.
* **4xx is excluded from the API error rate.** A client sending bad requests is not an outage of
  this service.
* **Backlog gauges alongside rates**, for the reason given above.
* **`NoEventsReceived` is the alert no error rate can replace**: everything looks healthy because
  nothing is happening. It is `info` here only because an idle demo stack is expected.

---

## 4. Health and readiness policy

| Probe | Contributors | Rationale |
|---|---|---|
| `/actuator/health/liveness` | `livenessState` only | Liveness answers "should this process be restarted", which is true only for states a restart can fix. A dependency being down is not one: restarting does not bring PostgreSQL back, it just removes the instance that would have recovered on its own. Putting a dependency in liveness turns an infrastructure blip into a restart storm across every instance at once. |
| `/actuator/health/readiness` | `readinessState`, `db`, `kafka` | Readiness answers "should traffic come here". |
| `/actuator/health` | Everything, including `redis`, `diskSpace`, `ping` | The full picture, for a human. |

**PostgreSQL is required for readiness.** It is the source of truth; without it neither a read nor a
write is correct.

**Kafka is required for readiness.** Consuming the topic is this service's primary job, and an
instance that cannot reach the broker is not doing it however healthy its HTTP port looks. Spring
Boot ships no Kafka health contributor, so `KafkaHealthIndicator` provides one: a `describeCluster`
call on a long-lived admin client with a 1.5 s timeout. Short and bounded, because a probe that
hangs is worse than one that reports DOWN — a hung probe is indistinguishable from a hung process.

**Redis is deliberately excluded from readiness.** It is a read accelerator with a TTL and every
value in it is derived from PostgreSQL, so an outage costs read latency and never correctness.
Failing readiness on it would take a healthy instance out of rotation for a problem it is designed
to absorb. It still appears in `/actuator/health`, so the outage is visible without being fatal:
during a Redis stop, `/actuator/health` returns 503 with `redis: DOWN` while
`/actuator/health/readiness` returns 200 and reads keep working. That behaviour is verified in
`OperationalEndpointsIntegrationTest` and reproducible by hand — see experiment 2 below.

### Actuator exposure

Exposed: `health`, `info`, `metrics`, `prometheus`. An allowlist, not a wildcard.

Not exposed: `env` and `configprops` (they include the datasource password), `beans`, `mappings`,
`loggers` (writable — an unauthenticated caller could change log levels), `threaddump`, `heapdump`
(a heap dump contains every in-flight payload), `shutdown`. JMX exposure is disabled entirely.

**What this build does not do:** actuator shares the API port and `show-details: always` is on, so
health responses name the database vendor, the Redis version and the broker's cluster id. That is
fine on a laptop and is reconnaissance if the port is reachable from outside. A real deployment
would set `management.server.port` to a port that is not routed publicly, and leave the details on
for the internal caller. Moving it here would break the Compose health check and every MockMvc
assertion for no local benefit, so it is documented rather than done.

---

## 5. Structured logging

JSON is on in Docker Compose (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`) and off for a local
`./gradlew bootRun`, because a JSON line per event is what a log store wants and what a human
reading a console does not. Set `LOG_FORMAT=` in the environment to get plain text back from
Compose.

The format is Elastic Common Schema. A real published schema rather than an ad-hoc shape, so
`correlationId` lands next to `log.level` and `service.name` somewhere that already knows how to
index it.

### Fields

An ingest line, as actually emitted:

```json
{
  "@timestamp": "2026-08-06T19:40:29.589406420Z",
  "log": { "level": "INFO", "logger": "ca.vm.parcelflow.tracking.messaging.CarrierTrackingEventListener" },
  "process": { "pid": 1, "thread": { "name": "...KafkaListenerEndpointContainer#0-2-C-1" } },
  "service": { "name": "tracking-service", "version": "0.1.0", "environment": "local" },
  "traceId": "6a74e32debec889ea7671a5eea126686",
  "spanId": "a7671a5eea126686",
  "correlationId": "a7351d94-d5eb-ed23-d840-b65e12a73de3",
  "eventId": "bc75fa9f-9233-b894-7d62-59c6d87d5ed4",
  "shipmentId": "16aa91e2-94f9-427f-b214-561c684046b7",
  "carrierCode": "SWIFTPOST",
  "topic": "carrier-tracking-events",
  "partition": "2",
  "offset": "8",
  "message": "Received carrier event eventId=... type=SP_CREATED sequence=1 partition=2 offset=0",
  "ecs": { "version": "8.11" }
}
```

The identifiers appear both as fields and inside the message. Redundant in a log store, deliberately
so everywhere else: a line pasted into a ticket still says which event it is.

`LogContext` declares every field name in one place, because these names are a contract with the log
store — the searches below name them, and a rename that touched one call site would break saved
queries silently. It is scoped and restores previous values on close: consumer and servlet threads
are pooled, and an MDC entry left behind attributes the next record to the wrong parcel.

### Correlation

* **HTTP**: `CorrelationIdFilter` reads `X-Correlation-Id`, generates one when absent, puts it in the
  context and echoes it on the response — including on error responses, because the request someone
  needs to trace is usually the one that failed. Actuator paths are excluded so a scrape every five
  seconds does not mint an id.
* **Kafka**: the listener takes `correlationId` off the message, and generates one if the producer
  omitted it. Logging the literal `null` is the worst option: a missing correlation id is exactly the
  situation where someone is trying to trace something unusual.
* **Failed-event handling**: `FailedEventRecoverer` establishes its own context, because it runs
  after the listener's has closed. Without it, the one line an operator most needs — "this event was
  dead-lettered" — would be the only line in the flow with no correlation id.
* **`traceId` / `spanId`**: W3C trace context, propagated across the HTTP and Kafka boundaries by
  Micrometer Tracing with the Brave bridge. No exporter is configured, so spans are created and
  dropped — the value here is log correlation, not a trace viewer. Adding an OTLP exporter and a
  Tempo container is a dependency and a Compose service away, and is not claimed to be done.

### What is never logged

* No customer personal information. `customerId` is an opaque key that resolves to a row somebody has
  to be authorized to read; no name, address or contact detail is logged anywhere.
* No complete raw payloads at INFO. A failed payload is *stored* in `failed_events` — where an
  operator has to ask for it — and never written to a log stream that gets shipped and indexed.
* No stack traces for expected conditions. A duplicate logs one INFO line with the exception's class
  name and no trace; a trace on an expected condition teaches operators to ignore traces.

### Levels

| Level | Used for |
|---|---|
| `DEBUG` | Per-event detail: what was recorded, an optimistic-lock retry, a cache eviction. |
| `INFO` | Ingest and outcome of each event, duplicates, out-of-order arrivals, notifications created, a successful manual retry. |
| `WARN` | Something absorbed but worth knowing: a Kafka retry, a dead letter, a failed event recorded, a swallowed Redis failure, giving up after repeated lock conflicts. |
| `ERROR` | Something that lost data or should not happen: the failed-event row could not be written, the DLT publish failed, an unhandled exception in a request. |

### Troubleshooting searches

With JSON logs, `jq`:

```bash
# Everything for one parcel
docker compose logs tracking-service --no-log-prefix \
  | jq -c 'select(.shipmentId == "<uuid>")'

# One carrier publish, end to end, across both services
docker compose logs tracking-service --no-log-prefix \
  | jq -c 'select(.correlationId == "<id>") | {t: .["@timestamp"], lvl: .log.level, msg: .message}'

# Everything in one trace, including the HTTP request that triggered it
docker compose logs tracking-service --no-log-prefix \
  | jq -c 'select(.traceId == "<traceId>")'

# Warnings and errors only, most recent first
docker compose logs tracking-service --no-log-prefix \
  | jq -c 'select(.log.level == "WARN" or .log.level == "ERROR") | {t: .["@timestamp"], msg: .message}'

# Everything that got dead-lettered
docker compose logs tracking-service --no-log-prefix \
  | jq -c 'select(.message | startswith("Dead-lettered")) | {eventId, topic, partition, offset, msg: .message}'

# Which partition and offset an event came from
docker compose logs tracking-service --no-log-prefix \
  | jq -c 'select(.eventId == "<uuid>") | {topic, partition, offset}'

# Duplicates in the last run, counted
docker compose logs tracking-service --no-log-prefix \
  | jq -c 'select(.message | contains("already processed"))' | wc -l
```

Without `jq`, or with plain-text logs, the identifiers are in the message text too:

```bash
docker compose logs tracking-service | grep '<shipmentId>'
```

---

## 6. Resilience verification

Seven experiments, all reproducible with Docker and `curl`. No chaos framework: at this size a
framework would be more machinery than the thing it tests, and these are meant to be run by hand
while watching a dashboard.

Common setup for all of them:

```bash
docker compose up -d --build
ID=$(curl -s -X POST http://localhost:8080/api/shipments \
  -H 'Content-Type: application/json' \
  -d '{"retailerId":"r","customerId":"c","trackingNumber":"SP-CHAOS-1","carrierCode":"SWIFTPOST"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["shipmentId"])')
```

Keep <http://localhost:3000> open in a browser throughout.

### Experiment 1 — restart the service during event production

**Setup.** A long simulator run so the restart lands mid-stream.

```bash
docker compose run --rm carrier-simulator --shipment-id=$ID --tracking-number=SP-CHAOS-1 \
  --carrier=SWIFTPOST --scenario=NORMAL --delay-ms=1500 &
sleep 4
docker compose restart tracking-service
```

**Expected.** No events are lost and none are double-applied. Offsets are committed after each
record, so a record in flight at the moment of the kill is redelivered on restart and takes the
duplicate path. `parcelflow_tracking_events_duplicate_total` may increase by one or two; the stored
history must not contain the same `event_id` twice, and the final status must be the same as an
uninterrupted run.

**Metrics.** `parcelflow_tracking_events_received_total` pauses and resumes;
`parcelflow_tracking_events_duplicate_total` may tick up; consumer lag spikes and drains.

**Logs.** `partitions revoked` then `partitions assigned`; possibly one
`Event ... already processed; no shipment or notification change`.

**Recovery.** Automatic.

**Consistency check.**

```bash
curl -s "http://localhost:8080/api/shipments/$ID/events?size=100" \
  | python3 -c 'import sys,json;e=json.load(sys.stdin)["content"];ids=[x["eventId"] for x in e];print("events",len(ids),"unique",len(set(ids)))'
```

`events` must equal `unique`.

### Experiment 2 — stop Redis

```bash
docker compose stop redis
curl -s -o /dev/null -w 'readiness %{http_code}\n' http://localhost:8080/actuator/health/readiness
curl -s -o /dev/null -w 'health    %{http_code}\n' http://localhost:8080/actuator/health
curl -s http://localhost:8080/api/shipments/$ID | python3 -m json.tool
docker compose run --rm carrier-simulator --shipment-id=$ID --tracking-number=SP-CHAOS-1 \
  --carrier=SWIFTPOST --scenario=NORMAL --delay-ms=100
docker compose start redis
```

**Expected.** Readiness stays 200. Aggregate health returns 503 with `redis: DOWN`, so the outage is
visible. Reads and ingest both keep working; reads get slower by one PostgreSQL round trip.

**Metrics.** `parcelflow_cache_failures_total{operation="get"}` and `{operation="evict"}` climb;
`cache_gets_total` stops moving; event counters are unaffected.

**Logs.** `Cache read failed ... falling through to PostgreSQL` at WARN. No ERROR.

**Recovery.** `docker compose start redis`. The cache repopulates on the next read; nothing has to
be invalidated by hand, because every entry has a TTL and was evicted-or-stale-at-worst.

**Consistency check.** Re-read the shipment after Redis returns and compare with the database:

```bash
docker compose exec postgres psql -U parcelflow -d parcelflow \
  -c "select current_status, version from shipments where shipment_id = '$ID'"
```

### Experiment 3 — stop PostgreSQL

```bash
docker compose stop postgres
curl -s -o /dev/null -w 'readiness %{http_code}\n' http://localhost:8080/actuator/health/readiness
curl -s -o /dev/null -w 'liveness  %{http_code}\n' http://localhost:8080/actuator/health/liveness
curl -s -o /dev/null -w 'read      %{http_code}\n' http://localhost:8080/api/shipments/$ID
docker compose start postgres
```

**Expected.** Readiness goes 503 — this is the dependency that must remove the instance from
rotation. Liveness stays 200, because a restart would not help. Reads fail with 500 after the 3 s
Hikari connection timeout rather than hanging. Events being consumed fail, are classified
`INFRASTRUCTURE` (retryable), and are retried with backoff; if the outage outlasts the retry budget
they are dead-lettered, and the failed-event row cannot be written either — which is why the DLT
publish happens regardless and the recoverer logs an ERROR saying so.

**Metrics.** `parcelflow_tracking_events_failed_total{category="INFRASTRUCTURE"}` climbs; the
backlog gauges freeze at their last values, which is deliberate — a database outage should not blank
the panels.

**Logs.** `Retrying carrier-tracking-events-N@M after ...` at WARN, then possibly
`Could not persist the failed-event record ...` at ERROR.

**Recovery.** `docker compose start postgres`. In-flight retries succeed. Anything dead-lettered
during the outage is in the DLT topic; if its failed-event row was also lost, the topic is the only
copy — replay it with the simulator or a console producer.

**Consistency check.** No partial writes are possible: the history insert and the shipment update
are one local transaction, so a shipment cannot show a status no stored event justifies.

```bash
docker compose exec postgres psql -U parcelflow -d parcelflow -c "
  select s.shipment_id, s.current_status, s.last_sequence_number,
         (select count(*) from tracking_events e where e.shipment_id = s.shipment_id) as events
  from shipments s where s.shipment_id = '$ID'"
```

### Experiment 4 — stop the broker

```bash
docker compose stop redpanda
curl -s http://localhost:8080/actuator/health/readiness | python3 -m json.tool
curl -s -o /dev/null -w 'API read %{http_code}\n' http://localhost:8080/api/shipments/$ID
docker compose start redpanda
```

**Expected.** Readiness goes 503 with `kafka: DOWN` and a reason. The HTTP read path still works —
it does not touch Kafka — which is exactly why readiness has to include Kafka explicitly: without
it, an instance that can serve reads but cannot consume events would look ready.

**Metrics.** Consumer lag stops reporting; `up{job="tracking-service"}` stays 1 (the process is
fine).

**Logs.** Repeated connection warnings from the Kafka client, at WARN.

**Recovery.** `docker compose start redpanda`. The consumer rejoins, readiness returns to 200, and
anything published while the broker was down is consumed from the committed offset — nothing is
skipped, because `auto-offset-reset: earliest` and the group's offsets survive in the broker.

**Consistency check.** Publish while the broker is down (the simulator will fail to send), then
after recovery, and confirm the post-recovery events land.

### Experiment 5 — poison messages

```bash
docker compose run --rm carrier-simulator --shipment-id=$ID --tracking-number=SP-CHAOS-1 \
  --carrier=SWIFTPOST --scenario=INVALID_EVENT --seed=11

# Something that is not even valid JSON for the deserializer
docker compose exec -T redpanda rpk topic produce carrier-tracking-events -k "$ID" <<< 'not json at all'
```

**Expected.** Neither stops the consumer. The invalid event is classified `VALIDATION`, skips the
retry backoff entirely (retrying it could never succeed), gets a `failed_events` row and a dead
letter. The unparseable bytes are classified `MALFORMED_PAYLOAD` and are dead-lettered with the raw
bytes preserved — which is what an operator needs to see what the producer actually sent.

**Metrics.** `parcelflow_tracking_events_failed_total{category="VALIDATION"}` and
`{category="MALFORMED_PAYLOAD"}`, `parcelflow_tracking_events_dlt_total`, and
`parcelflow_failed_events_awaiting_review` all increase.

**Logs.** `Recorded failed event ... category=VALIDATION` at WARN, then `Dead-lettered ...` at WARN.
No stack trace, and no payload.

**Recovery.**

```bash
curl -s 'http://localhost:8080/api/admin/failed-events?status=FAILED' | python3 -m json.tool
docker compose exec redpanda rpk topic consume carrier-tracking-events-dlt -n 2 --offset start
```

Neither category is retryable — retrying identical invalid bytes cannot succeed — so the workflow is
to fix the producer, not the retry button.

**Consistency check.** The shipment is untouched: `version` and `current_status` are what they were
before the poison arrived.

### Experiment 6 — duplicate burst

```bash
docker compose run --rm carrier-simulator --shipment-id=$ID --tracking-number=SP-CHAOS-1 \
  --carrier=SWIFTPOST --scenario=DUPLICATE --seed=7
```

**Expected.** 12 messages published, 8 events stored, 3 notifications. Duplicates are a success path,
not a failure path.

**Metrics.** `parcelflow_tracking_events_duplicate_total` +4;
`parcelflow_tracking_events_failed_total` unchanged;
`parcelflow_tracking_event_processing_duration_seconds_count{outcome="duplicate"}` +4.

**Logs.** Four `Event ... already processed; no shipment or notification change` at INFO. No traces.

**Consistency check.**

```bash
curl -s "http://localhost:8080/api/shipments/$ID/events?size=100" \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print("stored",d["totalElements"])'
curl -s "http://localhost:8080/api/shipments/$ID/notifications" \
  | python3 -c 'import sys,json;print("notifications",json.load(sys.stdin)["totalElements"])'
```

A customer must not be told twice that the same parcel was delivered.

### Experiment 7 — rapid concurrent events

```bash
docker compose run --rm carrier-simulator --shipment-id=$ID --tracking-number=SP-CHAOS-1 \
  --carrier=SWIFTPOST --scenario=RAPID_CONCURRENT_EVENTS --seed=3
```

**Expected.** Every event is stored exactly once and the final status matches the highest sequence
number applied. Optimistic locking may force retries; each retry re-reads and re-decides, so no
update is lost.

**Metrics.** `parcelflow_tracking_events_optimistic_lock_conflicts_total` may increase — zero is a
normal outcome here too, since events for one parcel share a partition key and therefore one
consumer thread; conflicts require a second writer, such as a manual retry running concurrently.

**Logs.** `Optimistic-lock conflict on event ..., attempt N of M; retrying` at DEBUG.

**Consistency check.** The shipment's `version` equals the number of applied events, and the events
are unique by `event_id`. A lost update would show as a version lower than the applied count.

---

## 7. Routine operator tasks

**Work the failed-event queue.**

```bash
curl -s 'http://localhost:8080/api/admin/failed-events?status=FAILED&size=50' | python3 -m json.tool
curl -s http://localhost:8080/api/admin/failed-events/<id> | python3 -m json.tool
curl -s -X POST http://localhost:8080/api/admin/failed-events/<id>/retry | python3 -m json.tool
```

Only categories that could plausibly succeed on a retry are accepted; the rest return 409 with the
category, which is the API saying "fix the producer, not the button". `/api/admin/**` is
unauthenticated in this build — it exposes cross-retailer failure detail and can trigger
reprocessing — and would need an operator role and a private network in a real deployment.

**Inspect the dead letter topic.**

```bash
docker compose exec redpanda rpk topic consume carrier-tracking-events-dlt -n 10 --offset start
```

Spring's recoverer stamps the original topic, partition, offset, exception class and message into
headers.

**Check consumer group state.**

```bash
docker compose exec redpanda rpk group describe tracking-service
docker compose exec redpanda rpk topic list
```

**Query the database directly.**

```bash
docker compose exec postgres psql -U parcelflow -d parcelflow -c "
  select current_status, count(*) from shipments group by 1 order by 2 desc"
```

---

## 8. What would change in a real deployment

Not done here, and not pretended to be. Listed because knowing the gap is part of operating a
system:

* **Management on its own port**, not routed publicly, and actuator behind network policy.
* **`/api/admin/**` behind authentication** and an operator role.
* **Alertmanager**, with routing, silences and an on-call rotation. Rules without a receiver are
  documentation.
* **A trace backend** — an OTLP exporter and Tempo or Jaeger — and sampling well below 100%.
* **Log shipping** to something that indexes the ECS fields, with a retention policy.
* **Redis and PostgreSQL exporters**, so the dashboard shows the dependencies and not only the
  application's view of them.
* **Multiple instances** behind a load balancer that actually consults the readiness probe, and more
  partitions than instances.
* **Secrets from a secret manager.** The database password is in the Compose file here.
