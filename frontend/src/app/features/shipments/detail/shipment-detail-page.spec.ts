import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import {
  countPage,
  notification,
  page,
  provideTestHttp,
  shipment,
  SHIPMENT_ID,
  trackingEvent,
} from '../../../../testing/http';
import { signInAs } from '../../../../testing/auth';
import { DEFAULT_POLL_CONFIG, POLL_CONFIG } from '../../../core/live/poll-policy';
import { ShipmentDetailPage } from './shipment-detail-page';

const URL = `/api/shipments/${SHIPMENT_ID}`;

describe('ShipmentDetailPage', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<ShipmentDetailPage>;

  const created = trackingEvent({
    eventId: 'aaaaaaaa-0000-4000-8000-000000000001',
    sequenceNumber: 1,
    normalizedEventType: 'LABEL_CREATED',
    carrierEventType: 'SP_CREATED',
    eventTime: '2026-08-01T08:00:00Z',
  });
  const pickedUp = trackingEvent({
    eventId: 'aaaaaaaa-0000-4000-8000-000000000002',
    sequenceNumber: 2,
    normalizedEventType: 'PICKED_UP',
    carrierEventType: 'SP_PICKUP',
    eventTime: '2026-08-01T12:00:00Z',
  });

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [ShipmentDetailPage],
      providers: [
        ...provideTestHttp(),
        provideRouter([]),
        // Keep the next poll far away; this test is about one loaded state.
        { provide: POLL_CONFIG, useValue: { ...DEFAULT_POLL_CONFIG, fastMs: 600_000, slowMs: 600_000 } },
      ],
    });
    signInAs('ADMIN');
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ShipmentDetailPage);
    // Attached, so focus() and document.activeElement behave as in a browser.
    document.body.appendChild(fixture.nativeElement);
    fixture.componentRef.setInput('shipmentId', SHIPMENT_ID);
    TestBed.tick();

    http.expectOne(URL).flush(shipment({ currentStatus: 'PICKED_UP', version: 2 }));
    http.expectOne((r) => r.url === `${URL}/events` && r.params.get('size') === '1').flush(countPage(2));
    http.expectOne((r) => r.url === `${URL}/notifications` && r.params.get('size') === '1').flush(countPage(1));
    http.expectOne((r) => r.url === `${URL}/events` && r.params.get('size') === '200').flush(page([created, pickedUp]));
    http
      .expectOne((r) => r.url === `${URL}/notifications` && r.params.get('size') === '100')
      .flush(page([notification({ sourceEventId: pickedUp.eventId, notificationType: 'PICKED_UP' })]));
    await fixture.whenStable();
  });

  afterEach(() => {
    fixture.nativeElement.remove();
    http.verify();
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const selectedTab = () => el().querySelector('[role="tab"][aria-selected="true"]')?.textContent?.trim() ?? '';
  const tab = (name: string) =>
    Array.from(el().querySelectorAll<HTMLElement>('[role="tab"]')).find((t) => t.textContent?.includes(name));

  it('navigates from a notification to its source event in the tracking history', async () => {
    tab('Notifications')?.click();
    await fixture.whenStable();
    expect(selectedTab()).toContain('Notifications');
    // The history tab is not rendered while Notifications is shown: the reason a plain fragment
    // link could never work.
    expect(el().querySelector(`#event-${pickedUp.eventId}`)).toBeNull();

    const link = el().querySelector<HTMLButtonElement>('[data-testid="notification-record"] .pf-link-button');
    expect(link?.tagName).toBe('BUTTON');
    expect(link?.getAttribute('aria-label')).toBe('Show event SP_PICKUP #2 in tracking history');
    link?.click();
    await fixture.whenStable();

    expect(selectedTab()).toContain('Tracking history');
    const row = el().querySelector<HTMLElement>(`#event-${pickedUp.eventId}`);
    expect(row).not.toBeNull();
    expect(document.activeElement).toBe(row);
    expect(row?.classList).toContain('pf-event-highlight');
    // Only the requested event is highlighted.
    expect(el().querySelectorAll('.pf-event-highlight')).toHaveLength(1);
  });

  it('can repeat the navigation after the user has moved away again', async () => {
    for (let i = 0; i < 2; i++) {
      tab('Notifications')?.click();
      await fixture.whenStable();
      el().querySelector<HTMLButtonElement>('.pf-link-button')?.click();
      await fixture.whenStable();
      expect(selectedTab()).toContain('Tracking history');
      expect(document.activeElement?.id).toBe(`event-${pickedUp.eventId}`);
    }
  });

  it('shows the current status from the shipment record', () => {
    expect(el().querySelector('[data-testid="current-status"]')?.getAttribute('data-status')).toBe('PICKED_UP');
    expect(el().querySelector('[data-testid="shipment-version"]')?.textContent).toBe('2');
  });
});
