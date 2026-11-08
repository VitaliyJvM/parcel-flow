import type { CarrierCode } from '../../core/api/models';
import { parseJavaLong } from './java-long';

/** Mirrors `carrier-simulator`'s `Scenario` enum and `CarrierEventScript` carriers. */
export type SimulatorScenario =
  | 'NORMAL'
  | 'DUPLICATE'
  | 'OUT_OF_ORDER'
  | 'INVALID_EVENT'
  | 'UNKNOWN_CARRIER_EVENT'
  | 'RAPID_CONCURRENT_EVENTS';

export const SIMULATOR_CARRIERS: readonly CarrierCode[] = ['SWIFTPOST', 'PACIFICA'];

export interface ScenarioInfo {
  id: SimulatorScenario;
  /**
   * What the scenario publishes. Based on the simulator's Scenario descriptions, made more precise
   * where the CLI wording is ambiguous (see UNKNOWN_CARRIER_EVENT).
   */
  summary: string;
  /** What to look for in this UI afterwards. */
  observe: string;
  /** Where the result shows up. */
  showsIn: 'shipment' | 'failed-events';
}

export const NORMAL_SCENARIO: ScenarioInfo = {
  id: 'NORMAL',
  summary: "Publishes the carrier's full journey once.",
  observe:
    'Status advances step by step to Delivered. Every event is Applied; notification records ' +
    'appear for picked up, out for delivery and delivered.',
  showsIn: 'shipment',
};

export const SCENARIOS: readonly ScenarioInfo[] = [
  NORMAL_SCENARIO,
  {
    id: 'DUPLICATE',
    summary: 'Republishes some events verbatim to exercise idempotency.',
    observe:
      'History and notification counts match a NORMAL run: duplicates (same event ID) are ' +
      'detected and never stored twice.',
    showsIn: 'shipment',
  },
  {
    id: 'OUT_OF_ORDER',
    summary: 'Publishes the journey with the middle events reordered.',
    observe:
      'Every event is kept, but those arriving after a higher sequence number are Superseded. ' +
      'Switch the timeline to “Arrival order” to see why. Status still ends at Delivered.',
    showsIn: 'shipment',
  },
  {
    id: 'INVALID_EVENT',
    summary: 'Publishes an event that fails validation.',
    observe:
      'Nothing changes on the shipment. A failed event with category VALIDATION appears under ' +
      'Failed events; it is not retryable.',
    showsIn: 'failed-events',
  },
  {
    id: 'UNKNOWN_CARRIER_EVENT',
    // The carrier itself is supported (SWIFTPOST/PACIFICA); only the *event type* is unknown: the
    // simulator sends the undocumented code SP_TELEPORTED (ScenarioEventFactory.unknownEventType).
    summary:
      'Publishes a valid event for the supported carrier, but with an event-type code that ' +
      "carrier's normalizer has no mapping for (SP_TELEPORTED).",
    observe:
      'Nothing changes on the shipment. A failed event with category UNKNOWN_EVENT_TYPE (not ' +
      'UNSUPPORTED_CARRIER) appears under Failed events; it is not retryable.',
    showsIn: 'failed-events',
  },
  {
    id: 'RAPID_CONCURRENT_EVENTS',
    summary: 'Publishes the whole journey back-to-back, with no delay between events.',
    observe:
      'The journey lands within about a second and the result matches NORMAL. This stresses the ' +
      'ingest path under a burst; it does not prove simultaneous processing of the same ' +
      "shipment: one parcel's events share a Kafka partition and are processed in order. Lock " +
      'contention is covered by the backend concurrency integration test instead.',
    showsIn: 'shipment',
  },
];

export interface SimulatorCommandInput {
  shipmentId: string;
  trackingNumber: string;
  carrier: CarrierCode;
  scenario: SimulatorScenario;
  /** Decimal string, kept exact; must be a non-negative Java long. Empty/null = CLI default. */
  delayMs?: string | null;
  /** Decimal string, kept exact; must be a Java long. Empty/null = random seed. */
  seed?: string | null;
}

/**
 * POSIX shell quoting: values made only of safe characters are left bare (readable), anything
 * else is single-quoted with embedded quotes escaped. A tracking number is free text, so this is
 * what stops a pasted command from doing something other than what it shows.
 */
export function shellQuote(value: string): string {
  return /^[A-Za-z0-9._:/=@%+-]+$/.test(value) ? value : `'${value.replace(/'/g, `'\\''`)}'`;
}

/**
 * Builds the documented `docker compose run` invocation (see docker-compose.yml and
 * SimulationRequest.USAGE). Run from the repository root. Nothing is executed by the browser.
 *
 * Numeric options are emitted in canonical decimal form from an exact BigInt parse, so the command
 * carries precisely the value the user typed. An invalid value throws instead of producing a
 * command the CLI would reject or, worse, accept with a different number.
 */
export function buildSimulatorCommand(input: SimulatorCommandInput): string {
  const args = [
    `--shipment-id=${shellQuote(input.shipmentId.trim())}`,
    `--tracking-number=${shellQuote(input.trackingNumber.trim())}`,
    `--carrier=${input.carrier}`,
    `--scenario=${input.scenario}`,
  ];
  const delay = optionalLong('delayMs', input.delayMs, true);
  if (delay !== null) {
    args.push(`--delay-ms=${delay}`);
  }
  const seed = optionalLong('seed', input.seed, false);
  if (seed !== null) {
    args.push(`--seed=${seed}`);
  }
  return ['docker compose run --rm carrier-simulator', ...args].join(' \\\n  ');
}

function optionalLong(name: string, raw: string | null | undefined, nonNegative: boolean): string | null {
  if (raw === null || raw === undefined || raw.trim() === '') {
    return null;
  }
  const parsed = parseJavaLong(raw, { nonNegative });
  if (parsed === null) {
    throw new RangeError(`${name} must be a ${nonNegative ? 'non-negative ' : ''}Java long, got '${raw}'`);
  }
  return parsed.toString();
}
