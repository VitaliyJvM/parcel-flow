# Demo script

Five to ten minutes, twelve steps, every command copy-pasteable. The point is not that a parcel gets
delivered — it is what happens when the event stream misbehaves.

**Before you start** (this part is not in the five minutes):

```bash
docker compose down -v
docker compose up --build -d
docker compose build carrier-simulator      # behind a profile, so `up --build` skips it
```

The first build takes a few minutes. Wait for readiness, then open <http://localhost:3000> in a
browser and leave the ParcelFlow dashboard visible — it is the backdrop for the whole demo.

```bash
until curl -fsS http://localhost:8080/actuator/health/readiness >/dev/null 2>&1; do sleep 2; done
echo "ready"
```

A helper used throughout:

```bash
register() {
  curl -s -X POST http://localhost:8080/api/shipments \
    -H 'Content-Type: application/json' \
    -d "{\"retailerId\":\"retailer-42\",\"customerId\":\"cust-9f13\",\"trackingNumber\":\"$1\",\"carrierCode\":\"SWIFTPOST\"}" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["shipmentId"])'
}
```

---

## 1. The stack is up

```bash
docker compose ps
```

Six containers: PostgreSQL, Redpanda, Redis, the tracking service, Prometheus, Grafana. The service
reports healthy only after Flyway has migrated and the broker is reachable, because its Compose
health check hits the readiness probe rather than the port.

```bash
curl -s http://localhost:8080/actuator/health/readiness | python3 -m json.tool
```

> Readiness includes `db` and `kafka` and deliberately excludes `redis`. Redis is a cache; an outage
> costs latency, not correctness, so it must not be able to take a working instance out of rotation.
> Step 11 demonstrates that.

## 2. Register a shipment

```bash
ID=$(register SP-DEMO-001)
echo $ID
curl -s http://localhost:8080/api/shipments/$ID | python3 -m json.tool
```

`LABEL_CREATED`, `version: 0`, no `lastEventTime`. The API is documented at
<http://localhost:8080/swagger-ui.html>.

## 3. Publish a normal carrier scenario

The carrier publishes *its own* event vocabulary to Kafka — `SP_CREATED`, `SP_PICKUP`, `SP_TRANSIT`,
`SP_DEPOT`, `SP_OFD`, `SP_DELIVERED`. ParcelFlow normalizes it.

```bash
docker compose run --rm carrier-simulator \
  --shipment-id=$ID --tracking-number=SP-DEMO-001 \
  --carrier=SWIFTPOST --scenario=NORMAL --delay-ms=300
```

## 4. Current status and full history

```bash
curl -s http://localhost:8080/api/shipments/$ID | python3 -m json.tool
```

`DELIVERED`, and `version: 8` — one optimistic-lock increment per applied event.

```bash
curl -s "http://localhost:8080/api/shipments/$ID/events?size=50" \
  | python3 -c 'import sys,json;[print(f"{e[\"sequenceNumber\"]:>2}  {e[\"carrierEventType\"]:<14} -> {e[\"normalizedEventType\"]:<20} {e[\"processingStatus\"]}") for e in json.load(sys.stdin)["content"]]'
```

Every entry keeps both readings: the carrier's code and ParcelFlow's normalized status. And the
notifications the milestones produced:

```bash
curl -s http://localhost:8080/api/shipments/$ID/notifications \
  | python3 -c 'import sys,json;[print(n["notificationType"], n["status"]) for n in json.load(sys.stdin)["content"]]'
```

Three: picked up, out for delivery, delivered. Not eight — `IN_TRANSIT` and `ARRIVED_AT_FACILITY`
repeat many times per journey and are not worth a message.

## 5. Publish duplicate events

Kafka is at-least-once. A rebalance replays an uncommitted offset however the consumer is tuned, so
redelivery is not an edge case — it is the normal operating condition.

```bash
DUP=$(register SP-DEMO-DUP)
docker compose run --rm carrier-simulator \
  --shipment-id=$DUP --tracking-number=SP-DEMO-DUP \
  --carrier=SWIFTPOST --scenario=DUPLICATE --seed=7 --delay-ms=100
```

The simulator reports **12 events published**.

## 6. Show that duplicates change nothing

```bash
curl -s "http://localhost:8080/api/shipments/$DUP/events?size=50" \
  | python3 -c 'import sys,json;print("events stored:", json.load(sys.stdin)["totalElements"])'
curl -s http://localhost:8080/api/shipments/$DUP \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print("status:", d["currentStatus"], " version:", d["version"])'
curl -s http://localhost:8080/api/shipments/$DUP/notifications \
  | python3 -c 'import sys,json;print("notifications:", json.load(sys.stdin)["totalElements"])'
```

12 published, **8 stored**, version 8, **3 notifications**. The customer is not told twice that their
parcel was delivered.

```bash
curl -s http://localhost:8080/actuator/prometheus | grep -E "^parcelflow_tracking_events_(duplicate|failed)_total" | grep -v " 0.0$"
```

Duplicates are counted as a **success** path, not a failure. Two mechanisms produce that: a
pre-check for the common redelivery, and a `UNIQUE (event_id)` constraint for the race the pre-check
cannot cover.

## 7. Publish an out-of-order scenario

```bash
OOO=$(register SP-DEMO-OOO)
docker compose run --rm carrier-simulator \
  --shipment-id=$OOO --tracking-number=SP-DEMO-OOO \
  --carrier=SWIFTPOST --scenario=OUT_OF_ORDER --seed=5 --delay-ms=100
```

## 8. Show that the current status does not regress

```bash
curl -s "http://localhost:8080/api/shipments/$OOO/events?size=50" \
  | python3 -c 'import sys,json;[print(f"{e[\"sequenceNumber\"]:>2}  {e[\"normalizedEventType\"]:<20} {e[\"processingStatus\"]}") for e in json.load(sys.stdin)["content"]]'
curl -s http://localhost:8080/api/shipments/$OOO \
  | python3 -c 'import sys,json;print("status:", json.load(sys.stdin)["currentStatus"])'
```

All eight events are in history — several marked `SUPERSEDED` — and the status is `DELIVERED`. A
late event is a real observation and is kept; it just does not move the parcel backwards. Ordering
authority is the carrier's per-shipment `sequenceNumber`, with `eventTime` as the tie-break.

## 9. Publish an invalid event

```bash
INV=$(register SP-DEMO-INV)
docker compose run --rm carrier-simulator \
  --shipment-id=$INV --tracking-number=SP-DEMO-INV \
  --carrier=SWIFTPOST --scenario=INVALID_EVENT --seed=11
```

## 10. Show the failed-event and dead-letter handling

```bash
curl -s 'http://localhost:8080/api/admin/failed-events' | python3 -m json.tool
```

One row: `errorCategory: VALIDATION`, `retryableAutomatically: false`, `retryableManually: false`,
with the origin topic, partition and offset, the exception type and a bounded message — no stack
trace. The message itself survives too:

```bash
docker compose exec redpanda rpk topic consume carrier-tracking-events-dlt -n 1 --offset start
```

Two facts worth saying out loud: the database row is written **before** the DLT publish, because if
the broker is what is broken then losing the explanation as well as the message is strictly worse;
and a permanently invalid payload **skips the retry backoff entirely**, because spending five
retries on bytes that can never succeed just delays every other record on the partition.

```bash
curl -s -X POST http://localhost:8080/api/admin/failed-events/<id>/retry
```

Returns 409 with the category — the API saying "fix the producer, not the button". Categories that
*could* succeed on retry (`UNSUPPORTED_CARRIER` once the normalizer ships, `SHIPMENT_NOT_FOUND`,
`INFRASTRUCTURE`) are accepted.

**The consumer never stopped.** Publish another normal event to prove it:

```bash
docker compose run --rm carrier-simulator \
  --shipment-id=$ID --tracking-number=SP-DEMO-001 \
  --carrier=SWIFTPOST --scenario=NORMAL --delay-ms=50 >/dev/null
curl -s http://localhost:8080/actuator/prometheus | grep "^parcelflow_tracking_events_received_total"
```

## 11. Metrics, dashboards and a cache outage

Switch to **Grafana at <http://localhost:3000>**. Everything from the last few minutes is on the one
dashboard: throughput, processing latency quantiles, duplicates and out-of-order events, failures by
category, dead letters, consumer lag, notifications, the backlog gauges, JVM memory, CPU, and HTTP
latency.

Alert rules are at **<http://localhost:9090/alerts>** — failure rate, dead-letter rate, processing
latency, consumer lag, unreviewed backlog, API error rate.

Then kill the cache while the demo is running:

```bash
docker compose stop redis

curl -s -o /dev/null -w 'readiness %{http_code}\n' http://localhost:8080/actuator/health/readiness
curl -s -o /dev/null -w 'health    %{http_code}\n' http://localhost:8080/actuator/health
curl -s http://localhost:8080/api/shipments/$ID | python3 -c 'import sys,json;print("read still works:", json.load(sys.stdin)["currentStatus"])'
curl -s http://localhost:8080/actuator/prometheus | grep 'parcelflow_cache_failures_total' | grep -v ' 0.0$'

docker compose start redis
```

Readiness **200**, aggregate health **503** with `redis: DOWN`, reads still correct, and a counter
that makes the swallowed failures visible. Degradation without an outage, and without it being
silent.

Structured logs, while you are here:

```bash
docker compose logs tracking-service --no-log-prefix --tail 200 \
  | jq -c 'select(.shipmentId == "'$ID'") | {t: .["@timestamp"], lvl: .log.level, correlationId, eventId, partition, offset, msg: .message}' | tail -5
```

One JSON object per line, with `correlationId`, `eventId`, `shipmentId`, `carrierCode`, `topic`,
`partition`, `offset`, `traceId` and `spanId` as first-class fields. No customer data, and no raw
payloads.

## 12. Run the tests

```bash
./gradlew test
```

226 tests. The integration tests start real PostgreSQL, Redpanda and Redis containers through
Testcontainers — no mocked database and no embedded broker anywhere. To run a slice:

```bash
./gradlew :tracking-service:test --tests '*IdempotencyAndOrdering*'
./gradlew :tracking-service:test --tests '*DeadLetter*'
./gradlew :tracking-service:test --tests '*Metrics*'
```

And the load test, if there is time (about two and a half minutes):

```bash
export PARCELFLOW_LOAD_TESTING_ENABLED=true
docker compose up -d --force-recreate tracking-service
docker compose --profile perf run --rm k6 run /scripts/parcelflow-load-test.js
```

Watch the Grafana dashboard while it runs. At the end the script reconciles what it published
against what the service says it processed, which is the check that would catch a run reporting a
healthy throughput while quietly dead-lettering half the events. Measured results are in
[performance-results.md](performance-results.md).

---

## Talking points, if asked

* **Why one service?** The event consumer updates the shipment, appends history and creates the
  notification in one local transaction. Splitting them would add a distributed transaction to a
  problem that does not have one.
* **Why is a duplicate a success?** Because Kafka is at-least-once and the alternative — losing a
  parcel scan — is not recoverable. Duplicate handling is where at-least-once becomes
  effectively-once.
* **Why is the database the last line of defence for idempotency?** An in-memory set dies in exactly
  the crash that causes duplicates, and a Redis check has no atomicity with the write.
* **Why keep superseded events?** They are real observations. Dropping them would make history a
  summary rather than a record, and the "why did the customer see that?" question unanswerable.
* **Why is Redis optional?** Because a cache that can take the service down is not a cache. The whole
  test suite runs with it disabled, which is the proof rather than the claim.
