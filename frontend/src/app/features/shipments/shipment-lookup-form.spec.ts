import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { problem, provideTestHttp, shipment } from '../../../testing/http';
import { createLookupForm, ShipmentLookupForm, toLookupQuery } from './shipment-lookup-form';

describe('lookup form model', () => {
  it('requires a tracking number and rejects whitespace-only values', () => {
    const form = createLookupForm();
    expect(form.controls.carrierCode.value).toBe('SWIFTPOST');
    expect(form.controls.trackingNumber.hasError('required')).toBe(true);
    form.controls.trackingNumber.setValue('   ');
    expect(form.controls.trackingNumber.hasError('blank')).toBe(true);
    form.controls.trackingNumber.setValue('SP1');
    expect(form.valid).toBe(true);
  });

  it('enforces the backend @Size(max = 128) limit', () => {
    const form = createLookupForm();
    form.controls.trackingNumber.setValue('t'.repeat(129));
    expect(form.controls.trackingNumber.hasError('maxlength')).toBe(true);
    form.controls.trackingNumber.setValue('t'.repeat(128));
    expect(form.valid).toBe(true);
  });

  it('trims the tracking number but preserves its case', () => {
    expect(toLookupQuery({ carrierCode: 'PACIFICA', trackingNumber: '  Px-12ab \t' })).toEqual({
      carrierCode: 'PACIFICA',
      trackingNumber: 'Px-12ab',
    });
  });
});

describe('ShipmentLookupForm', () => {
  let http: HttpTestingController;

  async function setup() {
    TestBed.configureTestingModule({
      imports: [ShipmentLookupForm],
      providers: [...provideTestHttp(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ShipmentLookupForm);
    await fixture.whenStable();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    return { fixture, el: fixture.nativeElement as HTMLElement, navigate };
  }

  function type(el: HTMLElement, value: string) {
    const input = el.querySelector<HTMLInputElement>('input[formcontrolname="trackingNumber"]');
    if (!input) throw new Error('no tracking number input');
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  function submit(el: HTMLElement) {
    el.querySelector<HTMLButtonElement>('button[type="submit"]')?.click();
  }

  function setCarrier(fixture: { componentInstance: unknown }, carrier: string) {
    (
      fixture.componentInstance as { form: ReturnType<typeof createLookupForm> }
    ).form.controls.carrierCode.setValue(carrier as 'PACIFICA');
  }

  const lookupRequest = () => http.expectOne((r) => r.url === '/api/shipments/lookup');

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  it('does not call the API while the form is invalid', async () => {
    const { fixture, el, navigate } = await setup();
    submit(el);
    await fixture.whenStable();
    http.expectNone((r) => r.url === '/api/shipments/lookup');
    expect(el.textContent).toContain('Enter a tracking number.');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('looks up the trimmed number for the chosen carrier and opens the shipment', async () => {
    const { fixture, el, navigate } = await setup();
    setCarrier(fixture, 'PACIFICA');
    type(el, '  PX-777  ');
    submit(el);
    const req = lookupRequest();
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('carrierCode')).toBe('PACIFICA');
    expect(req.request.params.get('trackingNumber')).toBe('PX-777');
    req.flush(
      shipment({ shipmentId: 'found-id', carrierCode: 'PACIFICA', trackingNumber: 'PX-777' }),
    );
    await fixture.whenStable();
    expect(navigate).toHaveBeenCalledWith(['/shipments', 'found-id']);
  });

  it('shows a not-found message under the field on 404, cleared by changing carrier or number', async () => {
    const { fixture, el, navigate } = await setup();
    type(el, 'SP-MISSING');
    submit(el);
    const { body, opts } = problem(404, 'shipment-not-found', {
      carrierCode: 'SWIFTPOST',
      trackingNumber: 'SP-MISSING',
    });
    lookupRequest().flush(body, opts);
    await fixture.whenStable();
    expect(el.querySelector('[data-testid="lookup-not-found"]')?.textContent).toContain(
      'No SwiftPost shipment has this tracking number.',
    );
    expect(el.querySelector('pf-error-panel')).toBeNull();
    expect(navigate).not.toHaveBeenCalled();

    setCarrier(fixture, 'PACIFICA');
    await fixture.whenStable();
    expect(el.querySelector('[data-testid="lookup-not-found"]')).toBeNull();

    submit(el);
    const again = problem(404, 'shipment-not-found');
    lookupRequest().flush(again.body, again.opts);
    await fixture.whenStable();
    expect(el.querySelector('[data-testid="lookup-not-found"]')).not.toBeNull();
    type(el, 'SP-OTHER');
    await fixture.whenStable();
    expect(el.querySelector('[data-testid="lookup-not-found"]')).toBeNull();
  });

  it('maps a backend validation error onto the tracking number field', async () => {
    const { fixture, el } = await setup();
    type(el, 'SP-1');
    submit(el);
    const { body, opts } = problem(400, 'validation-failed', {
      errors: { trackingNumber: 'size must be between 0 and 128' },
    });
    lookupRequest().flush(body, opts);
    await fixture.whenStable();
    expect(el.textContent).toContain('size must be between 0 and 128');
    expect(el.querySelector('pf-error-panel')).toBeNull();
  });

  it('shows an error panel with the correlation id for a server failure, and can retry', async () => {
    const { fixture, el, navigate } = await setup();
    type(el, 'SP-1');
    submit(el);
    const { body, opts } = problem(500, 'internal-error', {}, 'corr-lookup');
    lookupRequest().flush(body, opts);
    await fixture.whenStable();
    const panel = el.querySelector('pf-error-panel');
    expect(panel?.textContent).toContain('Lookup failed');
    expect(panel?.textContent).toContain('corr-lookup');

    Array.from(panel?.querySelectorAll('button') ?? [])
      .find((b) => b.textContent?.includes('Try again'))
      ?.click();
    lookupRequest().flush(shipment({ shipmentId: 'after-retry' }));
    await fixture.whenStable();
    expect(el.querySelector('pf-error-panel')).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/shipments', 'after-retry']);
  });

  it('reports an unreachable backend after the transient retries are exhausted', async () => {
    const { fixture, el } = await setup();
    vi.useFakeTimers();
    type(el, 'SP-1');
    submit(el);
    // Initial attempt plus the interceptor's two retries (300 ms, 600 ms backoff).
    lookupRequest().error(new ProgressEvent('error'));
    await vi.advanceTimersByTimeAsync(300);
    lookupRequest().error(new ProgressEvent('error'));
    await vi.advanceTimersByTimeAsync(600);
    lookupRequest().error(new ProgressEvent('error'));
    fixture.detectChanges();
    expect(el.querySelector('pf-error-panel')?.textContent).toContain('Service unreachable');
  });
});
