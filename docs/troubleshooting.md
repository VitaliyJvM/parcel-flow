# Troubleshooting

Problems people actually hit with this stack, in the order they tend to hit them. Each entry gives
symptoms, likely causes, the commands that tell you which cause it is, and how to recover.

For the metric and log-field reference, see [operations.md](operations.md).

---

## 1. Kafka is not ready when the service starts

**Symptoms.** `tracking-service` logs a stream of
`Connection to node -1 (redpanda/172.x.x.x:9092) could not be established`. Readiness returns 503
with `kafka: DOWN`. The HTTP API answers reads normally.

**Likely causes.**

* Redpanda is still starting. Its health check allows up to two minutes, and Compose gates
  `tracking-service` on `service_healthy`, so this should not happen with `docker compose up` — but
  it does if the service was started on its own with `docker compose up tracking-service`, or if it
  is running on the host against a broker that is not up yet.
* Running locally with `./gradlew bootRun` and no broker on `localhost:19092`.
* The advertised listener is wrong for where the client is: containers must use `redpanda:9092`, the
  host must use `localhost:19092`.

**Diagnosis.**

```bash
docker compose ps redpanda
docker compose exec redpanda rpk cluster health
docker compose logs redpanda | tail -30
docker compose exec tracking-service printenv | grep KAFKA
curl -s http://localhost:8080/actuator/health | python3 -c 'import sys,json;print(json.load(sys.stdin)["components"]["kafka"])'
```

**Recovery.** The client retries forever on its own; nothing needs restarting once the broker is up.

```bash
docker compose up -d redpanda
# then confirm the consumer rejoined
docker compose logs tracking-service | grep "partitions assigned"
```

If the address is wrong rather than the broker being down, fix `SPRING_KAFKA_BOOTSTRAP_SERVERS` and
restart the service. **This is not a data-loss event**: `auto-offset-reset: earliest` and committed
group offsets mean everything published during the outage is consumed once the consumer rejoins.

---

## 2. Database migration failure

**Symptoms.** The service exits during startup. The log ends with a Flyway error —
`Validate failed: Migrations have failed validation`, `Detected failed migration to version N`, or
`Detected applied migration not resolved locally`. The container restarts in a loop.

**Likely causes.**

* A migration file was edited after it had already been applied. Flyway stores a checksum; changing
  an applied file is the single most common cause.
* A migration was renamed or removed while the database still records it.
* A migration genuinely failed halfway on a database that does not support transactional DDL — not
  PostgreSQL, so unlikely here.
* An entity mapping no longer matches the schema: this surfaces as a Hibernate
  `SchemaManagementException` rather than a Flyway one, because `ddl-auto: validate` is deliberate.

**Diagnosis.**

```bash
docker compose logs tracking-service | grep -iE "flyway|migration|schema" | head -30
docker compose exec postgres psql -U parcelflow -d parcelflow \
  -c 'select installed_rank, version, description, success, checksum from flyway_schema_history order by installed_rank'
ls tracking-service/src/main/resources/db/migration/
```

Compare the `checksum` column against what Flyway reports for the file it read.

**Recovery.** In local development the honest fix is to throw the database away — it holds nothing
anybody needs:

```bash
docker compose down -v
docker compose up -d --build
```

If the data matters, add a **new** migration that corrects the schema rather than editing an old
one. Never edit an applied migration. `flyway repair` fixes a checksum mismatch where the change was
genuinely cosmetic, but reach for it knowing that it makes the history agree with the files rather
than the other way round.

---

## 3. Docker port conflict

**Symptoms.** `docker compose up` fails with
`Bind for 0.0.0.0:5432 failed: port is already allocated`, or the same for 8080, 6379, 9090, 3000 or
19092.

**Likely causes.** A local PostgreSQL, Redis or Grafana installation; a previous ParcelFlow stack
that was not brought down; another project's Compose stack.

**Diagnosis.**

```bash
lsof -nP -iTCP:5432 -sTCP:LISTEN      # macOS and Linux
docker ps --format 'table {{.Names}}\t{{.Ports}}'
docker compose ls                      # other Compose projects still running
```

**Recovery.** Either stop the other thing, or move ParcelFlow's port. The ports are published in
`docker-compose.yml`; changing the host side only is enough, since containers talk to each other on
the internal network:

```yaml
    ports:
      - "15432:5432"
```

`brew services stop postgresql@17` and `brew services stop grafana` are the usual culprits on macOS.

---

## 4. The consumer is not receiving events

**Symptoms.** The simulator or the load test reports events published, but nothing appears in
`GET /api/shipments/{id}/events` and `parcelflow_tracking_events_received_total` does not move.

**Likely causes.**

* Published to a different topic than the one being consumed.
* Published to a different broker — the classic one is a host process publishing to
  `localhost:19092` while the service is inside Compose consuming from `redpanda:9092`. Both work;
  they are the same broker. But if you ran a *second* Redpanda, they are not.
* The consumer group already committed past those offsets, because the events were consumed in an
  earlier run and this is a redelivery of nothing.
* The listener container is not running — it stops after an unrecoverable container-level error.
* Events are being consumed and rejected. Check the failure counters before assuming they never
  arrived.

**Diagnosis.**

```bash
# Are the messages actually on the topic?
docker compose exec redpanda rpk topic consume carrier-tracking-events -n 5 --offset start

# Is the group assigned, and how far behind is it?
docker compose exec redpanda rpk group describe tracking-service

# Is the listener alive?
docker compose logs tracking-service | grep -E "partitions assigned|Consumer stopped"

# Were they received and rejected rather than never received?
curl -s http://localhost:8080/actuator/prometheus \
  | grep -E "parcelflow_tracking_events_(received|failed|dlt)_total"
curl -s 'http://localhost:8080/api/admin/failed-events' | python3 -m json.tool
```

`rpk group describe` is the decisive one: it shows the topic, the partitions, the committed offset
and the lag per partition. Lag greater than zero and not shrinking means the consumer is stuck; lag
zero with no rows in the database means they were consumed and rejected.

**Recovery.** Depends on the cause. To replay from the beginning for a fresh look, use a different
group id rather than resetting the shared one:

```bash
docker compose exec redpanda rpk group seek tracking-service --to start --topics carrier-tracking-events
```

Safe here precisely because processing is idempotent — a full replay produces duplicates and no
double-applied events. That is the property worth remembering: a system where replay is safe can be
debugged by replaying.

---

## 5. An event was sent to the dead letter topic

**Symptoms.** `parcelflow_tracking_events_dlt_total` increased; the `DeadLetterRateElevated` alert is
firing; a `Dead-lettered ...` WARN line appears in the log.

**Likely causes.** In descending order of likelihood: a payload that fails validation (an unknown
schema version, a missing required field), an event type with no mapping for that carrier, a carrier
that disagrees with the shipment's, an unknown shipment id that never appeared, or a retryable
failure whose budget ran out during a dependency outage.

**Diagnosis.** Start with the durable record, not the topic — the row explains, the topic only
preserves:

```bash
curl -s 'http://localhost:8080/api/admin/failed-events?status=FAILED' | python3 -m json.tool
curl -s http://localhost:8080/api/admin/failed-events/<failedEventId> | python3 -m json.tool

# The message itself, with the origin metadata Spring stamped into the headers
docker compose exec redpanda rpk topic consume carrier-tracking-events-dlt -n 5 --offset start

# Everything the service logged for that event
docker compose logs tracking-service --no-log-prefix | jq -c 'select(.eventId == "<uuid>")'
```

The `errorCategory` field answers what to do next:

| Category | Automatically retried | Manual retry accepted | What to do |
|---|---|---|---|
| `MALFORMED_PAYLOAD` | no | no | Fix the producer. |
| `VALIDATION` | no | no | Fix the producer. |
| `UNKNOWN_EVENT_TYPE` | no | no | Add the mapping, then republish. |
| `CARRIER_MISMATCH` | no | no | Data problem at the source. |
| `UNSUPPORTED_CARRIER` | no | **yes** | Deploy the normalizer, then retry. |
| `SHIPMENT_NOT_FOUND` | yes | yes | Usually a scan that beat registration; retry after the shipment exists. |
| `CONCURRENCY_CONFLICT` | yes | yes | Retry. |
| `INFRASTRUCTURE` | yes | yes | Fix the dependency, then retry. |
| `UNKNOWN` | no | yes | Read the error, then decide. |

**Recovery.**

```bash
curl -s -X POST http://localhost:8080/api/admin/failed-events/<failedEventId>/retry | python3 -m json.tool
```

A non-retryable category returns 409 with the category. There is no bulk replay — retry is one event
at a time — which is a known limitation.

---

## 6. Redis is unavailable

**Symptoms.** `/actuator/health` returns 503 with `redis: DOWN`. WARN lines saying
`Cache read failed ... falling through to PostgreSQL`. Read latency rises.
`parcelflow_cache_failures_total` climbs.

**What is not a symptom.** Readiness stays UP, ingest is unaffected, and every response is still
correct. That is the design, and this is the entry that says so: **a Redis outage is not an
incident**, it is a performance regression. See experiment 2 in [operations.md](operations.md).

**Diagnosis.**

```bash
docker compose ps redis
docker compose exec redis redis-cli ping
curl -s -o /dev/null -w 'readiness %{http_code}\n' http://localhost:8080/actuator/health/readiness
curl -s http://localhost:8080/actuator/prometheus | grep parcelflow_cache_failures_total
```

**Recovery.**

```bash
docker compose start redis
```

Nothing has to be invalidated by hand. Entries have a 5-minute TTL, and an eviction that failed
during the outage costs at most a stale read until the TTL expires. If that is unacceptable in the
moment:

```bash
docker compose exec redis redis-cli --scan --pattern 'parcelflow:shipment-tracking:*' | \
  xargs -r docker compose exec -T redis redis-cli del
```

To run without Redis entirely — a supported configuration, not a workaround — set
`PARCELFLOW_CACHE_ENABLED=false` and restart. Most of the test suite runs that way.

---

## 7. A stale Docker volume

**Symptoms.** Shipments from a previous session are still there. Flyway reports migrations that do
not exist in the source tree. Redpanda has topics with old offsets, so a "fresh" run consumes
nothing. Grafana shows a dashboard that no longer matches the repository.

**Likely cause.** `docker compose down` without `-v`. Named volumes — `postgres-data`,
`redpanda-data`, `prometheus-data`, `grafana-data` — survive it by design.

**Diagnosis.**

```bash
docker volume ls | grep parcelflow
docker compose exec postgres psql -U parcelflow -d parcelflow -c 'select count(*) from shipments'
docker compose exec redpanda rpk group describe tracking-service
```

**Recovery.**

```bash
docker compose down -v          # drops all four volumes
docker compose up -d --build
```

To drop one:

```bash
docker compose rm -sf redpanda && docker volume rm parcelflow_redpanda-data
```

The Grafana volume is worth knowing about: the dashboard and datasource are provisioned from files,
so removing `grafana-data` loses nothing but Grafana's own preferences.

---

## 8. Testcontainers cannot start

**Symptoms.** `./gradlew test` fails before any assertion with
`Could not find a valid Docker environment`, `Connection refused: /var/run/docker.sock`, or a
timeout pulling an image.

**Likely causes.**

* The Docker daemon is not running.
* Docker Desktop on macOS puts its socket at `~/.docker/run/docker.sock`, and Testcontainers looks
  at `/var/run/docker.sock`. Colima and Rancher Desktop have the same issue with different paths.
* No network access to pull `postgres:17-alpine`, `redpandadata/redpanda` or `redis:7-alpine`.
* Not enough memory allocated to the Docker VM for three containers plus a JVM.
* On Apple Silicon, an image with no `arm64` variant. All three used here have one.

**Diagnosis.**

```bash
docker info
docker context ls                # which context is active, and its socket path
echo $DOCKER_HOST
ls -l /var/run/docker.sock ~/.docker/run/docker.sock 2>/dev/null
docker pull postgres:17-alpine   # network and architecture in one command
```

**Recovery.**

```bash
# Point Testcontainers at the socket the active context actually uses
export DOCKER_HOST=unix://$HOME/.docker/run/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./gradlew test
```

Raise the Docker VM's memory to at least 4 GB. There is no in-memory fallback and there deliberately
is not one: a test suite that silently swaps H2 for PostgreSQL stops testing the migrations, the
constraints and the isolation behaviour the design depends on.

**In CI**, `ubuntu-latest` runners have a working daemon and this does not arise; that is why the
workflow needs no service containers or DinD setup.

---

## 9. The Grafana dashboard has no data

**Symptoms.** Panels say "No data". Some panels have data and others do not.

**Likely causes, cheapest check first.**

1. Nothing has happened yet. An idle stack has no event rate to draw. Run a scenario.
2. Prometheus is not scraping the service.
3. The time range is wrong — the default is the last 30 minutes; a run from an hour ago is off-screen.
4. Only the consumer-lag panel is empty: **expected while the consumer is idle**. The Kafka client
   reports `NaN` for `records-lag-max` when it has not fetched recently, and a NaN draws nothing.
   Publish some events and it appears.
5. The datasource did not provision — usually a YAML error in `monitoring/grafana/provisioning/`.
6. A metric name in a panel no longer matches what the application exposes.

**Diagnosis.**

```bash
# Is the target up?
curl -s 'http://localhost:9090/api/v1/targets?state=active' \
  | python3 -c 'import sys,json;[print(t["labels"]["job"], t["health"], t["lastError"]) for t in json.load(sys.stdin)["data"]["activeTargets"]]'

# Does Prometheus have the series the panel asks for?
curl -s --data-urlencode 'query=parcelflow_tracking_events_received_total' \
  http://localhost:9090/api/v1/query | python3 -m json.tool

# Does the application expose it at all?
curl -s http://localhost:8080/actuator/prometheus | grep parcelflow_tracking_events_received_total

# Did Grafana pick up the provisioning?
curl -s http://localhost:3000/api/datasources | python3 -m json.tool
curl -s 'http://localhost:3000/api/search?query=' | python3 -m json.tool
docker compose logs grafana | grep -i "provisioning\|error"
```

The order matters: application → Prometheus → Grafana. Checking Grafana first tells you the least.

**Recovery.**

```bash
docker compose restart prometheus grafana   # after editing anything under monitoring/
curl -X POST http://localhost:9090/-/reload # Prometheus config only, no restart needed
```

If the target is down with `connection refused`, Prometheus is scraping `tracking-service:8080` over
the Compose network — confirm the service name resolves from inside the Prometheus container:

```bash
docker compose exec prometheus wget -qO- http://tracking-service:8080/actuator/prometheus | head -3
```

---

## 10. A shipment's status did not update

**Symptoms.** Events were published for a parcel, but `GET /api/shipments/{id}` still shows the old
status.

**This is the one to diagnose in order**, because four completely different things look identical
from the outside, and three of them are correct behaviour.

**Step 1 — is the event in history at all?**

```bash
curl -s "http://localhost:8080/api/shipments/$ID/events?size=100" | python3 -m json.tool
```

If it is missing, this is problem 4 or problem 5 above: it never arrived, or it failed. Stop here.

**Step 2 — is it there with `processingStatus: SUPERSEDED`?**

Then the system is working. The event arrived with a sequence number lower than one already applied
— or after `DELIVERED`, which is terminal and sticky — so it was stored as a real observation
without moving the current status. A backfilled scan must not un-deliver a parcel.

```bash
curl -s "http://localhost:8080/api/shipments/$ID/events?size=100" \
  | python3 -c 'import sys,json;[print(e["sequenceNumber"], e["normalizedEventType"], e["processingStatus"]) for e in json.load(sys.stdin)["content"]]'
```

Compare against the shipment's `last_sequence_number`.

**Step 3 — is it there as `APPLIED` but the read shows something older?**

Then the answer is almost certainly the cache. Compare the API against the database:

```bash
curl -s http://localhost:8080/api/shipments/$ID | python3 -c 'import sys,json;print("api:", json.load(sys.stdin)["currentStatus"])'
docker compose exec postgres psql -U parcelflow -d parcelflow -t \
  -c "select current_status from shipments where shipment_id = '$ID'"
docker compose exec redis redis-cli get "parcelflow:shipment-tracking:$ID"
```

If the API and Redis agree with each other but disagree with PostgreSQL, an eviction was lost —
which happens when Redis was unreachable at commit time. It self-heals when the 5-minute TTL
expires; to fix it now:

```bash
docker compose exec redis redis-cli del "parcelflow:shipment-tracking:$ID"
```

And check `parcelflow_cache_failures_total{operation="evict"}`, which is the counter that exists for
exactly this.

**Step 4 — the status is right and the expectation was wrong.**

Statuses are not ranked, and not every event type maps to a distinct status. `SP_DEPOT` maps to
`ARRIVED_AT_FACILITY`, which repeats many times in a journey; two consecutive `SP_DEPOT` scans leave
the status unchanged and both are applied. Check what the carrier code actually normalizes to in
[event-processing.md](event-processing.md#2-normalization).
