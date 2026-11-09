import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { page, provideTestHttp, shipment } from '../../../testing/http';
import { FailedEventsApi, ShipmentsApi } from './tracking-api';

describe('ShipmentsApi / FailedEventsApi', () => {
  let controller: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: provideTestHttp() });
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => controller.verify());

  it('lists a retailer page with status, page and size as query parameters', () => {
    TestBed.inject(ShipmentsApi)
      .listForRetailer('retailer 42/x', { page: 2, size: 50 }, 'IN_TRANSIT')
      .subscribe();
    const req = controller.expectOne((r) => r.url === '/api/retailers/retailer%2042%2Fx/shipments');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('status')).toBe('IN_TRANSIT');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('size')).toBe('50');
    req.flush(page([shipment()]));
  });

  it('omits the status parameter when no filter is set', () => {
    TestBed.inject(ShipmentsApi).listForRetailer('r', { page: 0, size: 20 }, null).subscribe();
    const req = controller.expectOne((r) => r.url === '/api/retailers/r/shipments');
    expect(req.request.params.has('status')).toBe(false);
    req.flush(page([]));
  });

  it('looks a shipment up by carrier and tracking number, sending the number verbatim', () => {
    TestBed.inject(ShipmentsApi).lookup('PACIFICA', 'PX 1/2&3+4').subscribe();
    const req = controller.expectOne((r) => r.url === '/api/shipments/lookup');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('carrierCode')).toBe('PACIFICA');
    expect(req.request.params.get('trackingNumber')).toBe('PX 1/2&3+4');
    // Reserved characters must not leak into the query string unescaped.
    expect(req.request.urlWithParams).toContain('trackingNumber=PX%201/2%263%2B4');
    req.flush(shipment());
  });

  it('posts a registration body unchanged', () => {
    const body = {
      retailerId: 'r',
      customerId: 'c',
      trackingNumber: 'SP1',
      carrierCode: 'SWIFTPOST' as const,
      estimatedDeliveryDate: '2026-08-12',
    };
    TestBed.inject(ShipmentsApi).register(body).subscribe();
    const req = controller.expectOne('/api/shipments');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush(shipment(), { status: 201, statusText: 'Created' });
  });

  it('requests tracking events and notifications with explicit paging', () => {
    const api = TestBed.inject(ShipmentsApi);
    api.events('abc', { page: 1, size: 200 }).subscribe();
    api.notifications('abc', { page: 0, size: 100 }).subscribe();
    const events = controller.expectOne((r) => r.url === '/api/shipments/abc/events');
    expect(events.request.params.get('page')).toBe('1');
    expect(events.request.params.get('size')).toBe('200');
    events.flush(page([]));
    controller.expectOne((r) => r.url === '/api/shipments/abc/notifications').flush(page([]));
  });

  it('retries a failed event with an empty POST and lists with a status filter', () => {
    const api = TestBed.inject(FailedEventsApi);
    api.retry('f-1').subscribe();
    const retry = controller.expectOne('/api/admin/failed-events/f-1/retry');
    expect(retry.request.method).toBe('POST');
    expect(retry.request.body).toBeNull();
    retry.flush({});

    api.list({ page: 0, size: 20 }, 'FAILED').subscribe();
    const list = controller.expectOne((r) => r.url === '/api/admin/failed-events');
    expect(list.request.params.get('status')).toBe('FAILED');
    list.flush(page([]));
  });
});
