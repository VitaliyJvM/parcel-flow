import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import {
  correlationIdInterceptor,
  problemDetailsInterceptor,
  transientRetryInterceptor,
} from '../app/core/http/interceptors';
import type {
  FailedEventResponse,
  NotificationResponse,
  Page,
  ShipmentResponse,
  TrackingEventResponse,
} from '../app/core/api/models';

/** The production interceptor chain against Angular's testing backend. */
export function provideTestHttp() {
  return [
    provideHttpClient(
      withInterceptors([correlationIdInterceptor, problemDetailsInterceptor, transientRetryInterceptor]),
    ),
    provideHttpClientTesting(),
  ];
}

export const SHIPMENT_ID = '0b9d6a0e-6f2b-4b8a-9a43-0f0d6d3c2a11';

export function page<T>(content: T[], overrides: Partial<Page<T>> = {}): Page<T> {
  return {
    content,
    page: 0,
    size: Math.max(content.length, 1),
    totalElements: content.length,
    totalPages: content.length ? 1 : 0,
    hasNext: false,
    ...overrides,
  };
}

/** A count probe response: one item (or none) but the full totalElements. */
export function countPage<T>(total: number): Page<T> {
  return { content: [], page: 0, size: 1, totalElements: total, totalPages: total, hasNext: total > 1 };
}

export function shipment(overrides: Partial<ShipmentResponse> = {}): ShipmentResponse {
  return {
    shipmentId: SHIPMENT_ID,
    retailerId: 'retailer-42',
    trackingNumber: 'SP100000000042',
    carrierCode: 'SWIFTPOST',
    currentStatus: 'LABEL_CREATED',
    estimatedDeliveryDate: '2026-08-12',
    lastEventTime: null,
    version: 0,
    createdAt: '2026-08-01T10:00:00Z',
    updatedAt: '2026-08-01T10:00:00Z',
    ...overrides,
  };
}

let eventCounter = 0;

export function trackingEvent(overrides: Partial<TrackingEventResponse> = {}): TrackingEventResponse {
  eventCounter++;
  return {
    eventId: `00000000-0000-4000-8000-${String(eventCounter).padStart(12, '0')}`,
    normalizedEventType: 'IN_TRANSIT',
    carrierEventType: 'SP_TRANSIT',
    carrierCode: 'SWIFTPOST',
    eventTime: '2026-08-02T10:00:00Z',
    receivedAt: '2026-08-02T10:00:01Z',
    sequenceNumber: eventCounter,
    location: 'Rivermouth',
    description: 'Departed origin facility',
    processingStatus: 'APPLIED',
    correlationId: 'corr-1',
    ...overrides,
  };
}

export function notification(overrides: Partial<NotificationResponse> = {}): NotificationResponse {
  return {
    notificationId: '11111111-1111-4111-8111-111111111111',
    sourceEventId: '00000000-0000-4000-8000-000000000001',
    notificationType: 'PICKED_UP',
    channel: 'EMAIL',
    status: 'PENDING',
    createdAt: '2026-08-02T10:00:02Z',
    ...overrides,
  };
}

export function failedEvent(overrides: Partial<FailedEventResponse> = {}): FailedEventResponse {
  return {
    failedEventId: '22222222-2222-4222-8222-222222222222',
    eventId: '33333333-3333-4333-8333-333333333333',
    shipmentId: SHIPMENT_ID,
    errorCategory: 'SHIPMENT_NOT_FOUND',
    retryableAutomatically: true,
    retryableManually: true,
    errorType: 'ca.vm.parcelflow.shipment.ShipmentNotFoundException',
    errorMessage: 'Shipment not found',
    retryCount: 5,
    status: 'FAILED',
    originalTopic: 'carrier-tracking-events',
    originalPartition: 1,
    originalOffset: 42,
    firstFailedAt: '2026-08-02T10:00:00Z',
    lastFailedAt: '2026-08-02T10:00:05Z',
    ...overrides,
  };
}

/** Body + headers for a problem+json error response. */
export function problem(
  status: number,
  slug: string,
  extra: Record<string, unknown> = {},
  correlationId = 'srv-corr-1',
) {
  return {
    body: {
      type: `https://parcelflow.example/problems/${slug}`,
      title: slug,
      status,
      detail: `detail for ${slug}`,
      ...extra,
    },
    opts: {
      status,
      statusText: 'Error',
      headers: { 'Content-Type': 'application/problem+json', 'X-Correlation-Id': correlationId },
    },
  };
}
