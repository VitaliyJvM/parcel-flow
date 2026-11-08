import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { failedEvent, problem, provideTestHttp } from '../../../testing/http';
import { ApiError } from '../../core/api/api-error';
import { FailedEventRetryResponse } from '../../core/api/models';
import { canRetry, FailedEventDetailPage, interpretRetry } from './failed-event-detail-page';

const ID = '22222222-2222-4222-8222-222222222222';
const URL = `/api/admin/failed-events/${ID}`;

function retryResponse(overrides: Partial<FailedEventRetryResponse> = {}): FailedEventRetryResponse {
  return {
    failedEventId: ID,
    succeeded: true,
    status: 'RESOLVED',
    processingStatus: 'APPLIED',
    shipmentStatus: 'PICKED_UP',
    duplicate: false,
    detail: 'Event reprocessed and recorded as APPLIED',
    ...overrides,
  };
}

describe('retry interpretation', () => {
  it('treats HTTP 200 with succeeded=false as a failure', () => {
    const outcome = interpretRetry(
      retryResponse({ succeeded: false, status: 'FAILED', processingStatus: null, shipmentStatus: null, duplicate: null }),
    );
    expect(outcome.kind).toBe('failed-again');
  });

  it('distinguishes the two 409s', () => {
    const notRetryable = new ApiError(409, 't', 'd', 'failed-event-not-retryable', {}, null, { errorCategory: 'VALIDATION' });
    const busy = new ApiError(409, 't', 'd', 'failed-event-retry-not-available', {}, null, { status: 'RETRYING' });
    const a = interpretRetry(notRetryable);
    const b = interpretRetry(busy);
    expect(a.kind === 'rejected' && a.message).toContain('VALIDATION');
    expect(b.kind === 'rejected' && b.message).toContain('RETRYING');
  });

  it('warns that a network failure leaves the outcome unknown', () => {
    const outcome = interpretRetry(new ApiError(0, 't', 'd', null, {}, 'c'));
    expect(outcome.kind === 'rejected' && outcome.message).toContain('may or may not');
  });

  it('only offers retry for manually retryable events in FAILED', () => {
    expect(canRetry(failedEvent())).toBe(true);
    expect(canRetry(failedEvent({ status: 'RETRYING' }))).toBe(false);
    expect(canRetry(failedEvent({ status: 'RESOLVED' }))).toBe(false);
    expect(canRetry(failedEvent({ retryableManually: false, errorCategory: 'VALIDATION' }))).toBe(false);
  });
});

describe('FailedEventDetailPage', () => {
  let http: HttpTestingController;
  let confirm: boolean;

  async function setup() {
    confirm = true;
    TestBed.configureTestingModule({
      imports: [FailedEventDetailPage],
      providers: [
        ...provideTestHttp(),
        provideRouter([]),
        { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(confirm) }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(FailedEventDetailPage);
    fixture.componentRef.setInput('failedEventId', ID);
    TestBed.tick();
    http.expectOne(URL).flush(failedEvent());
    await fixture.whenStable();
    return fixture;
  }

  function clickRetry(el: HTMLElement) {
    const button = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Retry'));
    if (!button) throw new Error('no retry button');
    button.click();
  }

  afterEach(() => http.verify());

  it('shows diagnostics without any payload', async () => {
    const fixture = await setup();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Shipment not found');
    expect(el.textContent).toContain('carrier-tracking-events');
    expect(el.textContent).toContain('deliberately not returned');
  });

  it('reports a successful retry and reloads the record', async () => {
    const fixture = await setup();
    clickRetry(fixture.nativeElement);
    http.expectOne(`${URL}/retry`).flush(retryResponse());
    TestBed.tick();
    http.expectOne(URL).flush(failedEvent({ status: 'RESOLVED' }));
    await fixture.whenStable();
    const outcome = (fixture.nativeElement as HTMLElement).querySelector('[data-testid="retry-outcome"]');
    expect(outcome?.textContent).toContain('Retry succeeded');
    expect(outcome?.textContent).toContain('Picked up');
  });

  it('reports HTTP 200 with succeeded=false as a failed retry', async () => {
    const fixture = await setup();
    clickRetry(fixture.nativeElement);
    http.expectOne(`${URL}/retry`).flush(
      retryResponse({
        succeeded: false,
        status: 'FAILED',
        processingStatus: null,
        shipmentStatus: null,
        duplicate: null,
        detail: 'Reprocessing failed again with category SHIPMENT_NOT_FOUND',
      }),
    );
    TestBed.tick();
    http.expectOne(URL).flush(failedEvent({ retryCount: 6 }));
    await fixture.whenStable();
    const outcome = (fixture.nativeElement as HTMLElement).querySelector('[data-testid="retry-outcome"]');
    expect(outcome?.textContent).toContain('processing failed again');
    expect(outcome?.textContent).toContain('SHIPMENT_NOT_FOUND');
  });

  it('reports a 409 conflict with its correlation id', async () => {
    const fixture = await setup();
    clickRetry(fixture.nativeElement);
    const { body, opts } = problem(409, 'failed-event-retry-not-available', { status: 'RETRYING' }, 'corr-409');
    http.expectOne(`${URL}/retry`).flush(body, opts);
    TestBed.tick();
    http.expectOne(URL).flush(failedEvent({ status: 'RETRYING' }));
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="retry-outcome"]')?.textContent).toContain('corr-409');
    // RETRYING records are not offered another retry.
    expect(Array.from(el.querySelectorAll('button')).some((b) => b.textContent?.includes('Retry…'))).toBe(false);
  });

  it('does nothing when the confirmation is cancelled', async () => {
    const fixture = await setup();
    confirm = false;
    clickRetry(fixture.nativeElement);
    http.expectNone(`${URL}/retry`);
  });
});
