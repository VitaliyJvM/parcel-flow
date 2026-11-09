import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { BACKGROUND_REQUEST } from '../auth/user-activity';
import { SKIP_RETRY } from '../http/interceptors';
import {
  CarrierCode,
  FailedEventResponse,
  FailedEventRetryResponse,
  FailedEventStatus,
  NotificationResponse,
  Page,
  PageRequest,
  RegisterShipmentRequest,
  ShipmentResponse,
  ShipmentStatus,
  TrackingEventResponse,
} from './models';

/**
 * Options shared by every read. `probe` marks a lightweight polling request that must not retry;
 * `background` marks a request the application sends on its own (a polling tick), which does not
 * count as user activity for the session's idle timeout.
 */
export interface ReadOptions {
  probe?: boolean;
  background?: boolean;
}

function params(values: Record<string, string | number | null | undefined>): HttpParams {
  let result = new HttpParams();
  for (const [key, value] of Object.entries(values)) {
    if (value !== null && value !== undefined && value !== '') {
      result = result.set(key, String(value));
    }
  }
  return result;
}

function context(options?: ReadOptions): HttpContext | undefined {
  if (!options?.probe && !options?.background) {
    return undefined;
  }
  return new HttpContext()
    .set(SKIP_RETRY, options.probe ?? false)
    .set(BACKGROUND_REQUEST, options.background ?? false);
}

/**
 * Thin typed wrapper over the public shipment, tracking and notification endpoints.
 *
 * One method per endpoint, relative same-origin URLs only, no caching and no state: the polling
 * logic that decides *when* to call these lives in `ShipmentLiveTracker`, and error parsing lives
 * in the interceptors.
 */
@Injectable({ providedIn: 'root' })
export class ShipmentsApi {
  private readonly http = inject(HttpClient);

  register(request: RegisterShipmentRequest): Observable<ShipmentResponse> {
    return this.http.post<ShipmentResponse>('/api/shipments', request);
  }

  get(shipmentId: string, options?: ReadOptions): Observable<ShipmentResponse> {
    return this.http.get<ShipmentResponse>(`/api/shipments/${encodeURIComponent(shipmentId)}`, {
      context: context(options),
    });
  }

  /**
   * `GET /api/shipments/lookup`: the shipment registered under a carrier's tracking number. The
   * backend matches the tracking number exactly (case-sensitive, untrimmed); callers decide whether
   * to trim. A miss is a 404 `shipment-not-found` `ApiError`.
   */
  lookup(carrierCode: CarrierCode, trackingNumber: string): Observable<ShipmentResponse> {
    return this.http.get<ShipmentResponse>('/api/shipments/lookup', {
      params: new HttpParams().set('carrierCode', carrierCode).set('trackingNumber', trackingNumber),
    });
  }

  listForRetailer(
    retailerId: string,
    page: PageRequest,
    status?: ShipmentStatus | null,
  ): Observable<Page<ShipmentResponse>> {
    return this.http.get<Page<ShipmentResponse>>(
      `/api/retailers/${encodeURIComponent(retailerId)}/shipments`,
      { params: params({ status, page: page.page, size: page.size }) },
    );
  }

  events(
    shipmentId: string,
    page: PageRequest,
    options?: ReadOptions,
  ): Observable<Page<TrackingEventResponse>> {
    return this.http.get<Page<TrackingEventResponse>>(
      `/api/shipments/${encodeURIComponent(shipmentId)}/events`,
      { params: params({ page: page.page, size: page.size }), context: context(options) },
    );
  }

  notifications(
    shipmentId: string,
    page: PageRequest,
    options?: ReadOptions,
  ): Observable<Page<NotificationResponse>> {
    return this.http.get<Page<NotificationResponse>>(
      `/api/shipments/${encodeURIComponent(shipmentId)}/notifications`,
      { params: params({ page: page.page, size: page.size }), context: context(options) },
    );
  }
}

/** ADMIN-only endpoints under `/api/admin/failed-events`; a RETAILER session gets 403. */
@Injectable({ providedIn: 'root' })
export class FailedEventsApi {
  private readonly http = inject(HttpClient);

  list(page: PageRequest, status?: FailedEventStatus | null): Observable<Page<FailedEventResponse>> {
    return this.http.get<Page<FailedEventResponse>>('/api/admin/failed-events', {
      params: params({ status, page: page.page, size: page.size }),
    });
  }

  get(failedEventId: string): Observable<FailedEventResponse> {
    return this.http.get<FailedEventResponse>(
      `/api/admin/failed-events/${encodeURIComponent(failedEventId)}`,
    );
  }

  /**
   * HTTP 200 does not mean the retry worked: inspect `succeeded`. 409 means the event is not
   * retryable or another retry holds it; both arrive as an `ApiError`.
   */
  retry(failedEventId: string): Observable<FailedEventRetryResponse> {
    return this.http.post<FailedEventRetryResponse>(
      `/api/admin/failed-events/${encodeURIComponent(failedEventId)}/retry`,
      null,
    );
  }
}
