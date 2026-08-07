/*
 * ParcelFlow load test.
 *
 * Three things run at once, because that is the only interesting shape: a carrier feed pushing
 * events into Kafka while customers read status and history over HTTP. Measuring ingest alone
 * would miss the contention that matters — the read path and the write path share a connection
 * pool, a JVM and a database.
 *
 * The event stream is not clean. A configurable share of it is duplicates and a configurable share
 * arrives out of order, because that is what a real carrier feed looks like and because the
 * idempotency and ordering machinery is precisely what this project is about. A load test against a
 * perfect event stream would measure a system nobody has.
 *
 * ── Configuration (all via environment variables) ────────────────────────────────────────────
 *
 *   BASE_URL                 default http://localhost:8080
 *   SHIPMENT_COUNT           shipments registered in setup           default 200
 *   VIRTUAL_USERS            concurrent readers, split across the
 *                            status and history scenarios            default 20
 *   TEST_DURATION            k6 duration string                      default 2m
 *   EVENT_RATE               carrier events per second (target)      default 200
 *   EVENTS_PER_REQUEST       events batched into one publish call    default 10
 *   DUPLICATE_PERCENTAGE     share of events that are exact repeats  default 10
 *   OUT_OF_ORDER_PERCENTAGE  share that arrive with a stale sequence default 10
 *
 * ── Running it ───────────────────────────────────────────────────────────────────────────────
 *
 *   PARCELFLOW_LOADTESTING_ENABLED=true docker compose up -d
 *   docker compose --profile perf run --rm k6 run /scripts/parcelflow-load-test.js
 *
 * The publish endpoint it uses is disabled by default and must be switched on for the run; see
 * CarrierEventLoadController for why it exists and why it is not something to leave enabled.
 *
 * ── What the thresholds are ──────────────────────────────────────────────────────────────────
 *
 * Starting points, not achievements. They were chosen as "what this ought to manage on a laptop",
 * and nothing in this repository claims they have been met — docs/performance-results.md records
 * measured numbers, and until a run is recorded there it says so.
 */

import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// ── Configuration ─────────────────────────────────────────────────────────────────────────────

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SHIPMENT_COUNT = intEnv('SHIPMENT_COUNT', 200);
const VIRTUAL_USERS = intEnv('VIRTUAL_USERS', 20);
const TEST_DURATION = __ENV.TEST_DURATION || '2m';
const EVENT_RATE = intEnv('EVENT_RATE', 200);
const EVENTS_PER_REQUEST = intEnv('EVENTS_PER_REQUEST', 10);
const DUPLICATE_PERCENTAGE = intEnv('DUPLICATE_PERCENTAGE', 10);
const OUT_OF_ORDER_PERCENTAGE = intEnv('OUT_OF_ORDER_PERCENTAGE', 10);

const RESULTS_DIR = __ENV.RESULTS_DIR || '/results';

/** Never DELIVERED: it is a terminal status, and a delivered parcel would supersede every later
 *  event, which would turn the measured out-of-order share into whatever the journey length was. */
const EVENT_TYPES = ['SP_PICKUP', 'SP_TRANSIT', 'SP_DEPOT', 'SP_TRANSIT', 'SP_OFD'];

const PUBLISH_RATE = Math.max(1, Math.round(EVENT_RATE / EVENTS_PER_REQUEST));
const STATUS_VUS = Math.max(1, Math.ceil(VIRTUAL_USERS / 2));
const HISTORY_VUS = Math.max(1, VIRTUAL_USERS - STATUS_VUS);

// ── Custom metrics ────────────────────────────────────────────────────────────────────────────

const eventsPublished = new Counter('parcelflow_events_published');
const duplicatesPublished = new Counter('parcelflow_duplicates_published');
const outOfOrderPublished = new Counter('parcelflow_out_of_order_published');
const publishBatchSize = new Trend('parcelflow_publish_batch_size');

export const options = {
    // Three scenarios, one clock. The readers use a fixed number of VUs because the question is
    // "what latency do N concurrent customers see"; the publisher uses an arrival rate because the
    // question there is "can the pipeline keep up with a feed running at R events per second",
    // which a VU-based executor cannot ask — it would slow down as the system slowed down and
    // quietly stop measuring the thing under test.
    scenarios: {
        ingest: {
            executor: 'constant-arrival-rate',
            exec: 'publishEvents',
            rate: PUBLISH_RATE,
            timeUnit: '1s',
            duration: TEST_DURATION,
            preAllocatedVUs: Math.max(4, Math.ceil(PUBLISH_RATE / 2)),
            maxVUs: Math.max(16, PUBLISH_RATE * 2),
            tags: { scenario: 'ingest' },
        },
        read_status: {
            executor: 'constant-vus',
            exec: 'readStatus',
            vus: STATUS_VUS,
            duration: TEST_DURATION,
            tags: { scenario: 'read_status' },
        },
        read_history: {
            executor: 'constant-vus',
            exec: 'readHistory',
            vus: HISTORY_VUS,
            duration: TEST_DURATION,
            tags: { scenario: 'read_history' },
        },
    },

    thresholds: {
        // Any 5xx, or a publish the broker refused, is a failure. 4xx from a deliberately
        // malformed request would be too, which is why this test does not send any.
        http_req_failed: ['rate<0.01'],

        // Per endpoint, because they are different questions. A cached status read should be fast;
        // a history page does a sorted, paged query; a publish is a Kafka round trip with acks=all.
        'http_req_duration{endpoint:status}': ['p(50)<50', 'p(95)<200', 'p(99)<500'],
        'http_req_duration{endpoint:history}': ['p(50)<100', 'p(95)<400', 'p(99)<800'],
        'http_req_duration{endpoint:publish}': ['p(95)<500', 'p(99)<1000'],

        // Aggregate, so a regression in any one of them is visible without reading three rows.
        http_req_duration: ['p(50)<100', 'p(95)<500', 'p(99)<1000'],

        checks: ['rate>0.99'],
    },

    // The summary prints these quantiles rather than k6's default p(90)/p(95).
    summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

// ── Setup: register the shipments, and take a baseline of the service's own counters ──────────

export function setup() {
    console.log(`Registering ${SHIPMENT_COUNT} shipments at ${BASE_URL}`);

    const runId = `${Date.now().toString(36)}`;
    const shipments = [];

    for (let i = 0; i < SHIPMENT_COUNT; i++) {
        // The tracking number carries a run id: it is unique per (carrier, tracking number) in the
        // database, so a second run against the same stack would otherwise 409 on every insert.
        const trackingNumber = `SP-LOAD-${runId}-${i}`;
        const response = http.post(
            `${BASE_URL}/api/shipments`,
            JSON.stringify({
                retailerId: `retailer-load-${i % 20}`,
                customerId: `cust-load-${i}`,
                trackingNumber: trackingNumber,
                carrierCode: 'SWIFTPOST',
            }),
            { headers: { 'Content-Type': 'application/json' }, tags: { endpoint: 'register' } },
        );

        if (response.status !== 201) {
            throw new Error(
                `Could not register shipment ${i}: HTTP ${response.status} ${response.body}`);
        }
        shipments.push({ id: response.json('shipmentId'), trackingNumber: trackingNumber });
    }

    const baseline = scrapeServiceCounters();
    if (baseline === null) {
        console.warn('Could not scrape /actuator/prometheus; server-side event counts will be '
            + 'reported as unavailable.');
    }

    console.log(`Registered ${shipments.length} shipments. Publishing at a target of ${EVENT_RATE} `
        + `events/s (${PUBLISH_RATE} requests/s x ${EVENTS_PER_REQUEST}), `
        + `${DUPLICATE_PERCENTAGE}% duplicates, ${OUT_OF_ORDER_PERCENTAGE}% out of order.`);

    return { shipments: shipments, baseline: baseline, startedAt: Date.now() };
}

// ── Ingest ────────────────────────────────────────────────────────────────────────────────────

/**
 * Publishes one batch of events.
 *
 * The shipment and the sequence numbers are derived from the global iteration counter rather than
 * from the VU, which is what makes the duplicate and out-of-order shares mean something: sequence
 * numbers for a given parcel advance monotonically across the whole run no matter which VU happens
 * to execute an iteration, so an event this function marks as out of order genuinely is one.
 */
export function publishEvents(data) {
    const shipments = data.shipments;
    const iteration = exec.scenario.iterationInTest;
    const shipment = shipments[iteration % shipments.length];
    const round = Math.floor(iteration / shipments.length);
    const baseSequence = round * EVENTS_PER_REQUEST + 1;

    const batch = [];
    let previous = null;

    for (let i = 0; i < EVENTS_PER_REQUEST; i++) {
        if (previous !== null && percentHit(DUPLICATE_PERCENTAGE)) {
            // Byte-identical, including the event id. This is what a Kafka redelivery looks like,
            // and the recorder's duplicate path is what should absorb it.
            batch.push(previous);
            duplicatesPublished.add(1);
            continue;
        }

        let sequence = baseSequence + i;
        let outOfOrder = false;

        if (round > 0 && percentHit(OUT_OF_ORDER_PERCENTAGE)) {
            // A scan from an earlier round, arriving now. Reaches the recorder after events with
            // higher sequence numbers, so it must be stored in history and must not move the
            // shipment's current status.
            sequence = Math.max(1, sequence - EVENTS_PER_REQUEST * (1 + Math.floor(random() * round)));
            outOfOrder = true;
            outOfOrderPublished.add(1);
        }

        const event = carrierEvent(shipment, sequence, outOfOrder);
        batch.push(event);
        previous = event;
    }

    const response = http.post(
        `${BASE_URL}/internal/load/carrier-events`,
        JSON.stringify(batch),
        { headers: { 'Content-Type': 'application/json' }, tags: { endpoint: 'publish' } },
    );

    const accepted = check(response, {
        'publish accepted': (r) => r.status === 202,
    });

    if (accepted) {
        eventsPublished.add(batch.length);
        publishBatchSize.add(batch.length);
    }
}

function carrierEvent(shipment, sequence, outOfOrder) {
    // Event time tracks the sequence number, so a stale sequence also carries a stale timestamp.
    // Sending a late event with a fresh timestamp would be testing a contradiction no carrier
    // produces, and would exercise the eventTime tie-break rather than the sequence comparison.
    const eventTime = new Date(Date.UTC(2026, 7, 1) + sequence * 3600_000).toISOString();

    return {
        eventId: uuid(),
        schemaVersion: 1,
        shipmentId: shipment.id,
        trackingNumber: shipment.trackingNumber,
        carrierCode: 'SWIFTPOST',
        eventType: EVENT_TYPES[sequence % EVENT_TYPES.length],
        eventTime: eventTime,
        sequenceNumber: sequence,
        location: 'Rivermouth',
        description: outOfOrder ? 'Backfilled scan' : 'Scan recorded',
        correlationId: `k6-${exec.scenario.iterationInTest}`,
    };
}

// ── Reads ─────────────────────────────────────────────────────────────────────────────────────

export function readStatus(data) {
    const shipment = randomShipment(data.shipments);

    const response = http.get(`${BASE_URL}/api/shipments/${shipment.id}`,
        { tags: { endpoint: 'status' } });

    check(response, {
        'status 200': (r) => r.status === 200,
        'status body has a current status': (r) => r.json('currentStatus') !== undefined,
    });

    // A think time, so the readers behave like customers refreshing a tracking page rather than a
    // tight loop that measures nothing except how fast one thread can spin.
    sleep(0.2 + random() * 0.3);
}

export function readHistory(data) {
    const shipment = randomShipment(data.shipments);

    const response = http.get(`${BASE_URL}/api/shipments/${shipment.id}/events?page=0&size=50`,
        { tags: { endpoint: 'history' } });

    check(response, {
        'history 200': (r) => r.status === 200,
        'history body is a page': (r) => r.json('content') !== undefined,
    });

    sleep(0.3 + random() * 0.5);
}

// ── Teardown: how much of what k6 published actually landed, and how long the drain took ──────

export function teardown(data) {
    if (data.baseline === null) {
        console.warn('No baseline was captured; skipping the server-side reconciliation.');
        return;
    }

    // Publishing has stopped but the consumer has not necessarily caught up. Waiting for it and
    // timing the wait is the closest this harness gets to measuring consumer lag directly: the
    // drain time is how long the pipeline needed to absorb the backlog the run built up.
    const drainTimeoutSeconds = 60;
    let drainSeconds = null;
    let final = null;
    let previousReceived = -1;

    for (let waited = 0; waited <= drainTimeoutSeconds; waited++) {
        final = scrapeServiceCounters();
        if (final === null) {
            break;
        }
        // Drained when the consumer has stopped taking new records off the topic and the client's
        // own lag metric agrees. Both conditions, because either one alone lies: lag can read zero
        // between fetches, and a stalled consumer also stops incrementing received.
        const settled = final.received === previousReceived
            && (final.lag === null || final.lag <= 0);
        if (settled) {
            drainSeconds = waited;
            break;
        }
        previousReceived = final.received;
        sleep(1);
    }

    if (final === null) {
        console.warn('Could not scrape /actuator/prometheus at the end of the run.');
        return;
    }

    const wallClockSeconds = (Date.now() - data.startedAt) / 1000;
    const processed = delta(data.baseline, final, 'processed');

    console.log('');
    console.log('── Server-side event counts (delta over the run) ────────────────────────────');
    console.log(`  received            ${delta(data.baseline, final, 'received')}`);
    console.log(`  processed           ${processed}`);
    console.log(`  applied             ${delta(data.baseline, final, 'applied')}`);
    console.log(`  duplicate           ${delta(data.baseline, final, 'duplicate')}`);
    console.log(`  out of order        ${delta(data.baseline, final, 'outOfOrder')}`);
    console.log(`  failed              ${delta(data.baseline, final, 'failed')}`);
    console.log(`  dead-lettered       ${delta(data.baseline, final, 'dlt')}`);
    console.log(`  processing rate     ${(processed / wallClockSeconds).toFixed(1)} events/s `
        + `(over ${wallClockSeconds.toFixed(0)}s wall clock, including setup and drain)`);
    console.log(`  consumer lag (max)  ${final.lag === null ? 'unavailable' : final.lag}`);
    console.log(`  drain after run     ${drainSeconds === null
        ? `still draining after ${drainTimeoutSeconds}s` : `${drainSeconds}s`}`);
    console.log('─────────────────────────────────────────────────────────────────────────────');
    console.log('These are counts, not a verdict. Copy them into docs/performance-results.md '
        + 'together with the machine and Docker settings they were produced on.');
}

// ── Summary output ────────────────────────────────────────────────────────────────────────────

/**
 * Writes a machine-readable summary next to a Markdown fragment shaped for
 * docs/performance-results.md, so recording a result is a copy rather than a transcription — which
 * is the difference between a results table that is accurate and one that is approximately
 * remembered.
 */
export function handleSummary(data) {
    const output = {
        stdout: textSummary(data),
    };
    output[`${RESULTS_DIR}/summary.json`] = JSON.stringify(data, null, 2);
    output[`${RESULTS_DIR}/summary.md`] = markdownSummary(data);
    return output;
}

function textSummary(data) {
    const lines = ['', 'ParcelFlow load test summary', ''];
    lines.push(`  configuration    ${SHIPMENT_COUNT} shipments, ${VIRTUAL_USERS} readers, `
        + `${EVENT_RATE} events/s target, ${TEST_DURATION}, `
        + `${DUPLICATE_PERCENTAGE}% duplicates, ${OUT_OF_ORDER_PERCENTAGE}% out of order`);
    lines.push('');
    for (const row of summaryRows(data)) {
        lines.push(`  ${row.label.padEnd(28)} ${row.value}`);
    }
    lines.push('');
    return lines.join('\n');
}

function markdownSummary(data) {
    const rows = summaryRows(data)
        .map((row) => `| ${row.label} | ${row.value} |`)
        .join('\n');

    return [
        '<!-- Generated by performance/k6/parcelflow-load-test.js. Paste into',
        '     docs/performance-results.md together with the environment it was produced on. -->',
        '',
        `Configuration: ${SHIPMENT_COUNT} shipments, ${VIRTUAL_USERS} readers, `
        + `${EVENT_RATE} events/s target, ${TEST_DURATION} duration, `
        + `${DUPLICATE_PERCENTAGE}% duplicates, ${OUT_OF_ORDER_PERCENTAGE}% out of order.`,
        '',
        '| Measurement | Value |',
        '|---|---|',
        rows,
        '',
    ].join('\n');
}

function summaryRows(data) {
    const rows = [];
    const push = (label, value) => rows.push({ label: label, value: value });

    push('requests', count(data, 'http_reqs'));
    push('request rate', `${rate(data, 'http_reqs').toFixed(1)}/s`);
    push('HTTP error rate', `${(rateValue(data, 'http_req_failed') * 100).toFixed(2)}%`);
    push('checks passed', `${(rateValue(data, 'checks') * 100).toFixed(2)}%`);
    push('p50 latency (all)', ms(data, 'http_req_duration', 'p(50)'));
    push('p95 latency (all)', ms(data, 'http_req_duration', 'p(95)'));
    push('p99 latency (all)', ms(data, 'http_req_duration', 'p(99)'));
    push('p95 status read', ms(data, 'http_req_duration{endpoint:status}', 'p(95)'));
    push('p95 history read', ms(data, 'http_req_duration{endpoint:history}', 'p(95)'));
    push('p95 event publish', ms(data, 'http_req_duration{endpoint:publish}', 'p(95)'));
    push('events published', count(data, 'parcelflow_events_published'));
    push('publish rate', `${rate(data, 'parcelflow_events_published').toFixed(1)} events/s`);
    push('duplicates published', count(data, 'parcelflow_duplicates_published'));
    push('out-of-order published', count(data, 'parcelflow_out_of_order_published'));
    return rows;
}

// ── Helpers ───────────────────────────────────────────────────────────────────────────────────

function intEnv(name, fallback) {
    const raw = __ENV[name];
    if (raw === undefined || raw === '') {
        return fallback;
    }
    const parsed = parseInt(raw, 10);
    if (isNaN(parsed)) {
        throw new Error(`${name} must be an integer, got "${raw}"`);
    }
    return parsed;
}

function random() {
    return Math.random();
}

function percentHit(percentage) {
    return percentage > 0 && random() * 100 < percentage;
}

function randomShipment(shipments) {
    return shipments[Math.floor(random() * shipments.length)];
}

/** Not crypto-quality, and does not need to be: it only has to not collide within one run. */
function uuid() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
        const r = (Math.random() * 16) | 0;
        const v = c === 'x' ? r : (r & 0x3) | 0x8;
        return v.toString(16);
    });
}

/**
 * Reads the service's own counters straight off the scrape endpoint.
 *
 * Reconciling what k6 sent against what the service says it processed is the only way to catch the
 * failure this harness could otherwise hide: a run that reports a fine publish rate while the
 * consumer silently dead-letters half of it would look like a success.
 */
function scrapeServiceCounters() {
    const response = http.get(`${BASE_URL}/actuator/prometheus`,
        { tags: { endpoint: 'scrape' } });
    if (response.status !== 200) {
        return null;
    }
    const body = response.body;
    return {
        received: sumMetric(body, 'parcelflow_tracking_events_received_total'),
        processed: sumMetric(body, 'parcelflow_tracking_events_processed_total'),
        applied: sumMetric(body, 'parcelflow_tracking_events_applied_total'),
        duplicate: sumMetric(body, 'parcelflow_tracking_events_duplicate_total'),
        outOfOrder: sumMetric(body, 'parcelflow_tracking_events_out_of_order_total'),
        failed: sumMetric(body, 'parcelflow_tracking_events_failed_total'),
        dlt: sumMetric(body, 'parcelflow_tracking_events_dlt_total'),
        lag: maxMetric(body, 'kafka_consumer_fetch_manager_records_lag_max'),
    };
}

/** Sums every labelled series for one metric name out of the Prometheus text exposition. */
function sumMetric(body, name) {
    let total = 0;
    for (const value of metricValues(body, name)) {
        total += value;
    }
    return total;
}

function maxMetric(body, name) {
    let max = null;
    for (const value of metricValues(body, name)) {
        max = max === null ? value : Math.max(max, value);
    }
    return max;
}

function metricValues(body, name) {
    const values = [];
    const lines = body.split('\n');
    for (let i = 0; i < lines.length; i++) {
        const line = lines[i];
        if (line.length === 0 || line.charCodeAt(0) === 35 /* # */ || line.indexOf(name) !== 0) {
            continue;
        }
        // Either `name value` or `name{labels} value`; anything else starting with the same prefix
        // is a different metric and must not be counted. Without this check,
        // parcelflow_tracking_events_received_total would also pick up a hypothetical
        // ..._received_total_bytes.
        const rest = line.slice(name.length);
        if (rest.length > 0 && rest[0] !== ' ' && rest[0] !== '{') {
            continue;
        }
        const value = parseFloat(line.slice(line.lastIndexOf(' ') + 1));
        if (!isNaN(value)) {
            values.push(value);
        }
    }
    return values;
}

function delta(baseline, final, key) {
    if (baseline[key] === null || final[key] === null) {
        return 0;
    }
    return Math.round(final[key] - baseline[key]);
}

function metric(data, name) {
    return data.metrics[name] || { values: {} };
}

function count(data, name) {
    const value = metric(data, name).values.count;
    return value === undefined ? 'n/a' : Math.round(value);
}

function rate(data, name) {
    const value = metric(data, name).values.rate;
    return value === undefined ? 0 : value;
}

function rateValue(data, name) {
    const value = metric(data, name).values.rate;
    return value === undefined ? 0 : value;
}

function ms(data, name, stat) {
    const value = metric(data, name).values[stat];
    return value === undefined ? 'n/a' : `${value.toFixed(1)}ms`;
}
