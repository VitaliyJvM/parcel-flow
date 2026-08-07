# Performance results

What ParcelFlow does under a mixed load of carrier events and customer reads, measured rather than
estimated.

> **Read this first.** These numbers come from one laptop running the whole system — application,
> database, broker, cache, Prometheus, Grafana and the load generator — inside Docker Desktop. They
> say something useful about whether the design has an obvious bottleneck at this scale. They say
> nothing about what the system would do on real hardware, with a network between the tiers, with a
> multi-broker cluster, or with a database holding more rows than fit in page cache. Nothing here is
> a production capacity claim.

---

## 1. Environment

| | |
|---|---|
| Machine | Apple M1, 8 cores, 16 GB |
| OS | macOS 26.5.2 |
| Docker | Docker Engine 29.1.3, overlayfs storage driver |
| Docker resources | 8 CPUs, 7.7 GB memory available to the VM |
| Runtime | openjdk version "25.0.3" 2026-04-21 LTS |
| Application | ParcelFlow `N/A`, 1 `tracking-service` instance(s), consumer `concurrency: 3` |
| PostgreSQL | `postgres:17-alpine`, Hikari pool 16 |
| Broker | `redpandadata/redpanda:v25.1.1`, N/A partitions, replication factor N/A |
| Cache | `redis:7-alpine`, TTL N/A |
| Load generator | `k6 v0.57.0 (commit/50afd82c18, go1.23.6, linux/arm64)`, compose mode on the same Docker host/network |

Everything shares the available Docker resources with the load generator. Contention between the
thing being measured and the thing measuring it is real here and would not exist in a proper test
setup, where the generator lives on a separate machine.

## 2. Test command

```bash
export PARCELFLOW_LOAD_TESTING_ENABLED=true

docker compose down -v
docker compose up -d --build

# Wait for readiness before starting: a run that begins during Flyway migration measures startup.
curl -fsS http://localhost:8080/actuator/health/readiness

SHIPMENT_COUNT=200 \
VIRTUAL_USERS=20 \
TEST_DURATION=2m \
EVENT_RATE=200 \
EVENTS_PER_REQUEST=10 \
DUPLICATE_PERCENTAGE=10 \
OUT_OF_ORDER_PERCENTAGE=10 \
docker compose --profile perf run --rm k6 run /scripts/parcelflow-load-test.js
```

`export`, not a one-off prefix: both Compose commands have to see the variable. Compose can
recreate a service whose resolved environment changed, so both startup and the test should run with
load testing enabled.

The measured run used:

| Parameter | Value |
|---|---|
| `SHIPMENT_COUNT` | 200 |
| `VIRTUAL_USERS` | 20 |
| `TEST_DURATION` | 2m |
| `EVENT_RATE` | 200 events/s target |
| `EVENTS_PER_REQUEST` | 10 |
| `DUPLICATE_PERCENTAGE` | 10 |
| `OUT_OF_ORDER_PERCENTAGE` | 10 |

## 3. Dataset

Measured at the end of the run.

| | |
|---|---|
| Shipments | 200 |
| Tracking events stored | 21875 |
| Notifications | 7949 |
| Failed events | 0 |
| Database size | 20 MB |

The database was created empty by `docker compose down -v`, so the row counts above belong to this
run. The dataset may still be small enough to fit in PostgreSQL's page cache; read latency for a
database larger than memory would be a different measurement.

---

## 4. Results

**Date of test: 2026-08-06.** Commit `aa246b4` on branch `stage4`. Duration
2m, plus setup and 0s measured drain time.

### Client-side (k6)

| Measurement | Value |
|---|---|
| Requests | 8161 |
| Request rate | 66.3/s |
| HTTP error rate | N/A |
| Checks passed | 100.00% |
| p50 latency, all endpoints | 1.4 ms |
| p95 latency, all endpoints | 4.8 ms |
| p99 latency, all endpoints | 13.2 ms |
| p95 `GET /api/shipments/{id}` | N/A ms |
| p95 `GET /api/shipments/{id}/events` | N/A ms |
| p95 event publish | N/A ms |
| Events published | N/A |
| Publish rate | N/A events/s |
| Duplicates published | 2125 |
| Out-of-order events published | 1858 |

k6 threshold result: **PASS**. Process exit code: `0`.

### Server-side (the service's own metrics, delta over the run)

| Measurement | Value |
|---|---|
| Events received | 24000 |
| Events processed | 24000 |
| Applied | 20017 |
| Duplicate | 2125 |
| Out of order | 1858 |
| Failed | 0 |
| Dead-lettered | 0 |
| Event processing rate | 186.0 events/s over 129s wall clock |
| Event processing p50 | 1.2 ms |
| Event processing p95 | 3.0 ms |
| Event processing p99 | 8.5 ms |
| Max Kafka consumer lag | 0 |
| Drain time after the run stopped | 0s |
| Peak JVM heap in use | 153 MB of 5.7 GB maximum |
| Peak process CPU | 53% |
| Cache hit ratio | 46.4% |

A value of **N/A** means the expected k6 custom metric, Actuator metric, Prometheus series, database
table or configuration property was not available under the expected name. The script leaves it
unfilled rather than inventing a value.

### Reconciliation

The client, server and database measurements should agree:

* N/A published versus 24000 received. Review the raw output, ingress endpoint and consumer metrics. Measurement unavailable for one or both sides.
* 2125 duplicates published = 2125 duplicates detected. Every measured redelivery took the idempotent path.
* 1858 out-of-order events published = 1858 recorded as out of order.
* Expected N/A stored tracking events from published minus duplicates; the database contains 21875. Measurement unavailable for one or both sides.
* 0 failed, 0 dead-lettered, and the failed-event table contains 0 rows.

A load test that reports only throughput and HTTP errors can look healthy even when events are
silently rejected or dead-lettered. Reconciliation checks whether that happened.

---

## 5. Observations

**Configured rate versus achieved rate.** The configured target was 200 events/s. Maximum measured consumer lag was zero, so this run did not establish the system's throughput ceiling.

**Event processing latency.** Measured event-processing latency was 1.2 ms at p50, 3.0 ms at p95, and 8.5 ms at p99 in this local environment.

**Tail latency.** The all-endpoints p99 of 13.2 ms was about 9.1× the p50 of 1.4 ms. Review the first seconds of the run, JIT compilation, connection warm-up and outliers before attributing the gap to steady-state load.

**Cache behaviour.** The measured cache hit ratio was 46.4%. This is a property of this read/write mix: frequent accepted events invalidate shipment cache entries.

**Heap behaviour.** Peak JVM heap in use was 153 MB against a reported maximum of 5.7 GB. A longer run is still required to assess retention or gradual growth.

## 6. Bottlenecks

The test may or may not have reached these limits. They are ordered by the current architecture and
the measurements available from this run:

1. **Consumer parallelism.** Consumer `concurrency: 3` is backed by
   N/A topic partitions. Threads beyond the partition count cannot add Kafka
   consumption parallelism.
2. **One write transaction per accepted event.** Every applied event normally requires history
   persistence and a shipment update. At higher rates PostgreSQL write throughput and fsync latency
   are likely to matter.
3. **Optimistic-lock contention on hot shipments.** A workload concentrated on a small number of
   shipments can retry whole transactions even if an evenly distributed workload does not.
4. **The Hikari pool.** The detected/configured pool size is 16, shared by event
   consumers and HTTP request handling.
5. **Cache invalidation frequency.** A write-heavy workload invalidates current-shipment cache
   entries frequently. This cannot become a source-of-truth correctness problem, but it can make the
   cache provide little benefit.

## 7. Improvements worth trying

* **Find the actual ceiling.** Use a `ramping-arrival-rate` scenario that increases the rate until
  consumer lag grows consistently.
* **More partitions and another service instance.** This would test rebalancing and whether
  idempotency remains correct when ownership moves between consumers.
* **Batch consumption.** Measure whether batching lowers transaction overhead without weakening
  failure handling.
* **Move the load generator off the machine under test.** This removes competition for the same CPU,
  memory and Docker networking.
* **Use a dataset larger than page cache.** That makes history-query indexes and storage I/O part of
  the measurement.
* **Run for longer.** A 2m run is not evidence about connection leaks, gradual heap
  growth or index bloat.

---

## 8. Reproducing this

The runner creates these files:

* `performance/results/summary.json` — latest complete k6 JSON summary.
* `performance/results/summary.md` — latest client-side Markdown summary.
* `performance/results/summary-20260806-215031.json` — versioned JSON summary for this run.
* `performance/results/output-20260806-215031.txt` — complete k6 console output.
* `performance/results/prometheus-before-20260806-215031.txt` and
  `performance/results/prometheus-after-20260806-215031.txt` — raw application metrics used for deltas.

To repeat the same test:

```bash
./scripts/run-performance-test.sh
```

To vary the shape of the load:

```bash
SHIPMENT_COUNT=1000 VIRTUAL_USERS=50 TEST_DURATION=5m EVENT_RATE=500 \
  EVENTS_PER_REQUEST=10 DUPLICATE_PERCENTAGE=25 OUT_OF_ORDER_PERCENTAGE=20 \
  ./scripts/run-performance-test.sh
```

Watch Grafana at <http://localhost:3000> and Prometheus at <http://localhost:9090> while the test runs.
