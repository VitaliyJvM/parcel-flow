import type { components } from './generated/tracking-service';

/**
 * API contracts used by the application, derived from the generated OpenAPI types.
 *
 * The generator runs with `--properties-required-by-default` because springdoc does not emit
 * `required` for Java record components, which would otherwise make every field optional. That
 * flag over-promises in the other direction for the handful of fields the backend can return as
 * `null`; those are corrected here, explicitly, rather than sprinkled as `!` through templates.
 * Each correction cites the Java field it mirrors.
 */
type Schemas = components['schemas'];

type Nullable<T, K extends keyof T> = Omit<T, K> & { [P in K]: T[P] | null };

export type CarrierCode = Schemas['ShipmentResponse']['carrierCode'];
export type ShipmentStatus = Schemas['ShipmentResponse']['currentStatus'];
export type EventProcessingStatus = Schemas['TrackingEventResponse']['processingStatus'];
export type NotificationType = Schemas['NotificationResponse']['notificationType'];
export type NotificationChannel = Schemas['NotificationResponse']['channel'];
export type NotificationStatus = Schemas['NotificationResponse']['status'];
export type ErrorCategory = Schemas['FailedEventResponse']['errorCategory'];
export type FailedEventStatus = Schemas['FailedEventResponse']['status'];

/** springdoc emits a `required` list here (from @NotBlank/@NotNull), so the ETA is already optional. */
export type RegisterShipmentRequest = Schemas['RegisterShipmentRequest'];

export type ShipmentResponse = Nullable<
  Schemas['ShipmentResponse'],
  // Shipment.estimatedDeliveryDate is optional; lastEventTime is null until the first event.
  'estimatedDeliveryDate' | 'lastEventTime'
>;

export type TrackingEventResponse = Nullable<
  Schemas['TrackingEventResponse'],
  // tracking_events.location / description are nullable columns (V2 migration).
  'location' | 'description'
>;

export type NotificationResponse = Schemas['NotificationResponse'];

export type FailedEventResponse = Nullable<
  Schemas['FailedEventResponse'],
  // event_id / shipment_id are null when the payload could not be parsed; error_message is
  // nullable (V5 migration).
  'eventId' | 'shipmentId' | 'errorMessage'
>;

export type FailedEventRetryResponse = Nullable<
  Schemas['FailedEventRetryResponse'],
  // Only populated when succeeded === true (FailedEventRetryResponse.from).
  'processingStatus' | 'shipmentStatus' | 'duplicate'
>;

/** `PageResponse<T>` envelope; identical for every paginated endpoint. */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}

export interface PageRequest {
  page: number;
  size: number;
}

/** Server-side page-size ceilings (`MAX_PAGE_SIZE` in each controller). */
export const PAGE_LIMITS = {
  retailerShipments: 100,
  trackingEvents: 200,
  notifications: 100,
  failedEvents: 100,
} as const;
