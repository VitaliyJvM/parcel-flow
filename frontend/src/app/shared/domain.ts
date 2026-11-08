import type {
  CarrierCode,
  ErrorCategory,
  FailedEventStatus,
  NotificationType,
  ShipmentStatus,
} from '../core/api/models';

/**
 * Presentation metadata for backend enums. Labels and explanations only — no business rules are
 * re-implemented here. Ordering, terminality and retry eligibility are decided by the backend and
 * read from its responses.
 */

export const SHIPMENT_STATUSES: readonly ShipmentStatus[] = [
  'LABEL_CREATED',
  'PICKED_UP',
  'IN_TRANSIT',
  'ARRIVED_AT_FACILITY',
  'OUT_FOR_DELIVERY',
  'DELAYED',
  'DELIVERY_ATTEMPTED',
  'DELIVERED',
];

export type StatusTone = 'neutral' | 'progress' | 'warning' | 'success';

export const SHIPMENT_STATUS_META: Record<
  ShipmentStatus,
  { label: string; icon: string; tone: StatusTone }
> = {
  LABEL_CREATED: { label: 'Label created', icon: 'label', tone: 'neutral' },
  PICKED_UP: { label: 'Picked up', icon: 'package_2', tone: 'progress' },
  IN_TRANSIT: { label: 'In transit', icon: 'local_shipping', tone: 'progress' },
  ARRIVED_AT_FACILITY: { label: 'At facility', icon: 'warehouse', tone: 'progress' },
  OUT_FOR_DELIVERY: { label: 'Out for delivery', icon: 'directions_car', tone: 'progress' },
  DELAYED: { label: 'Delayed', icon: 'schedule', tone: 'warning' },
  DELIVERY_ATTEMPTED: { label: 'Delivery attempted', icon: 'door_front', tone: 'warning' },
  DELIVERED: { label: 'Delivered', icon: 'check_circle', tone: 'success' },
};

export const CARRIERS: readonly CarrierCode[] = ['SWIFTPOST', 'PACIFICA', 'NORDEX', 'METROLINK'];

/**
 * Only these carriers have an event normalizer in the tracking service
 * (`SwiftPostEventNormalizer`, `PacificaEventNormalizer`) and a journey in the simulator
 * (`CarrierEventScript`). NORDEX and METROLINK can be registered, but every event for them is
 * dead-lettered as UNSUPPORTED_CARRIER.
 */
export const OPERATIONAL_CARRIERS: readonly CarrierCode[] = ['SWIFTPOST', 'PACIFICA'];

export function isOperationalCarrier(carrier: CarrierCode): boolean {
  return OPERATIONAL_CARRIERS.includes(carrier);
}

export const CARRIER_META: Record<CarrierCode, { label: string; note: string }> = {
  SWIFTPOST: { label: 'SwiftPost', note: 'Fictional national carrier · dense scan events' },
  PACIFICA: { label: 'Pacifica', note: 'Fictional express carrier' },
  NORDEX: { label: 'Nordex', note: 'No event normalizer yet — events fail as UNSUPPORTED_CARRIER' },
  METROLINK: {
    label: 'Metrolink',
    note: 'No event normalizer yet — events fail as UNSUPPORTED_CARRIER',
  },
};

export const NOTIFICATION_TYPE_LABEL: Record<NotificationType, string> = {
  PICKED_UP: 'Parcel picked up',
  OUT_FOR_DELIVERY: 'Out for delivery',
  DELAYED: 'Delivery delayed',
  DELIVERY_ATTEMPTED: 'Delivery attempted',
  DELIVERED: 'Delivered',
};

export const FAILED_EVENT_STATUSES: readonly FailedEventStatus[] = ['FAILED', 'RETRYING', 'RESOLVED'];

export const FAILED_EVENT_STATUS_META: Record<
  FailedEventStatus,
  { label: string; tone: StatusTone; explanation: string }
> = {
  FAILED: {
    label: 'Failed',
    tone: 'warning',
    explanation: 'Automatic retries were exhausted, or the failure was never retryable.',
  },
  RETRYING: {
    label: 'Retrying',
    tone: 'progress',
    explanation:
      'A manual retry holds this record. If it stays here, the retry did not finish and the ' +
      'backend currently has no endpoint to release it.',
  },
  RESOLVED: {
    label: 'Resolved',
    tone: 'success',
    explanation: 'A manual retry processed the event successfully.',
  },
};

/** Text adapted from the `ErrorCategory` Javadoc in the tracking service. */
export const ERROR_CATEGORY_META: Record<ErrorCategory, { label: string; explanation: string }> = {
  MALFORMED_PAYLOAD: {
    label: 'Malformed payload',
    explanation: 'The payload could not be deserialized at all. Re-running the same bytes cannot succeed.',
  },
  VALIDATION: {
    label: 'Validation',
    explanation:
      'A required field is missing, a constraint failed, or the schema version is unknown. Not retryable.',
  },
  UNKNOWN_EVENT_TYPE: {
    label: 'Unknown event type',
    explanation: 'The carrier sent an event code with no mapping in its normalizer. Not retryable.',
  },
  CARRIER_MISMATCH: {
    label: 'Carrier mismatch',
    explanation: "The event's carrier disagrees with the shipment's. Retrying cannot reconcile that.",
  },
  UNSUPPORTED_CARRIER: {
    label: 'Unsupported carrier',
    explanation:
      'No normalizer is registered for the carrier. Retryable by hand once a normalizer is deployed.',
  },
  SHIPMENT_NOT_FOUND: {
    label: 'Shipment not found',
    explanation:
      'The event referenced a shipment that did not exist yet — often a scan that beat the ' +
      'registration call. Retry once the shipment is registered.',
  },
  CONCURRENCY_CONFLICT: {
    label: 'Concurrency conflict',
    explanation: 'Two updates raced on one shipment. Retrying re-reads and re-decides.',
  },
  INFRASTRUCTURE: {
    label: 'Infrastructure',
    explanation: 'The database or another dependency was temporarily unavailable.',
  },
  UNKNOWN: {
    label: 'Unknown',
    explanation:
      'Unclassified failure. Not retried automatically; an operator can decide to retry by hand.',
  },
};

export function shortId(id: string | null | undefined): string {
  return id ? id.slice(0, 8) : '—';
}
