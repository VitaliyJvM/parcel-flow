import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { problem, provideTestHttp, shipment } from '../../../testing/http';
import { RetailerContext } from '../../core/retailer/retailer-context';
import { createRegisterForm, ShipmentRegisterPage, toRegisterRequest } from './shipment-register-page';

describe('registration form model', () => {
  it('requires identifiers and rejects whitespace-only values (@NotBlank)', () => {
    const form = createRegisterForm('');
    form.patchValue({ customerId: '   ', trackingNumber: 'SP1' });
    expect(form.controls.retailerId.hasError('required')).toBe(true);
    expect(form.controls.customerId.hasError('blank')).toBe(true);
    expect(form.controls.trackingNumber.valid).toBe(true);
    expect(form.valid).toBe(false);
  });

  it('enforces the backend @Size limits', () => {
    const form = createRegisterForm('r');
    form.patchValue({ customerId: 'c'.repeat(65), trackingNumber: 't'.repeat(129) });
    expect(form.controls.customerId.hasError('maxlength')).toBe(true);
    expect(form.controls.trackingNumber.hasError('maxlength')).toBe(true);
    form.patchValue({ customerId: 'c'.repeat(64), trackingNumber: 't'.repeat(128) });
    expect(form.valid).toBe(true);
  });

  it('builds a trimmed request and omits an empty ETA', () => {
    const request = toRegisterRequest({
      retailerId: ' r ',
      customerId: 'c',
      trackingNumber: ' SP1 ',
      carrierCode: 'PACIFICA',
      estimatedDeliveryDate: '',
    });
    expect(request).toEqual({ retailerId: 'r', customerId: 'c', trackingNumber: 'SP1', carrierCode: 'PACIFICA' });
    expect('estimatedDeliveryDate' in request).toBe(false);
  });

  it('sends the ETA as the date input string, never through a Date', () => {
    const request = toRegisterRequest({
      retailerId: 'r',
      customerId: 'c',
      trackingNumber: 'SP1',
      carrierCode: 'SWIFTPOST',
      estimatedDeliveryDate: '2026-08-12',
    });
    expect(request.estimatedDeliveryDate).toBe('2026-08-12');
  });
});

describe('ShipmentRegisterPage', () => {
  let http: HttpTestingController;

  async function setup() {
    TestBed.configureTestingModule({
      imports: [ShipmentRegisterPage],
      providers: [...provideTestHttp(), provideRouter([])],
    });
    localStorage.clear();
    TestBed.inject(RetailerContext).select('retailer-42');
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ShipmentRegisterPage);
    await fixture.whenStable();
    return fixture;
  }

  function fill(el: HTMLElement, name: string, value: string) {
    const input = el.querySelector<HTMLInputElement>(`input[formcontrolname="${name}"]`);
    if (!input) throw new Error(`no input ${name}`);
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  function submit(el: HTMLElement) {
    el.querySelector<HTMLButtonElement>('button[type="submit"]')?.click();
  }

  afterEach(() => http.verify());

  it('prefills the selected retailer and blocks submission while invalid', async () => {
    const fixture = await setup();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector<HTMLInputElement>('input[formcontrolname="retailerId"]')?.value).toBe('retailer-42');
    submit(el);
    await fixture.whenStable();
    http.expectNone('/api/shipments');
    expect(el.textContent).toContain('Customer reference is required.');
  });

  it('registers and navigates to the new shipment', async () => {
    const fixture = await setup();
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const el = fixture.nativeElement as HTMLElement;
    fill(el, 'customerId', 'cust-1');
    fill(el, 'trackingNumber', 'SP900');
    fill(el, 'estimatedDeliveryDate', '2026-08-12');
    submit(el);

    const req = http.expectOne('/api/shipments');
    expect(req.request.body).toEqual({
      retailerId: 'retailer-42',
      customerId: 'cust-1',
      trackingNumber: 'SP900',
      carrierCode: 'SWIFTPOST',
      estimatedDeliveryDate: '2026-08-12',
    });
    req.flush(shipment({ shipmentId: 'new-id' }), { status: 201, statusText: 'Created' });
    await fixture.whenStable();
    expect(navigate).toHaveBeenCalledWith(['/shipments', 'new-id'], { state: { justRegistered: true } });
  });

  it('maps a 409 duplicate onto the tracking number field', async () => {
    const fixture = await setup();
    const el = fixture.nativeElement as HTMLElement;
    fill(el, 'customerId', 'cust-1');
    fill(el, 'trackingNumber', 'SP900');
    submit(el);
    const { body, opts } = problem(409, 'duplicate-tracking-number', {
      carrierCode: 'SWIFTPOST',
      trackingNumber: 'SP900',
    });
    http.expectOne('/api/shipments').flush(body, opts);
    await fixture.whenStable();

    expect(el.textContent).toContain('Already registered');
    expect(el.textContent).toContain('Already registered for this carrier.');
    // Editing the field clears the server-side flag.
    fill(el, 'trackingNumber', 'SP901');
    await fixture.whenStable();
    expect(el.textContent).not.toContain('Already registered for this carrier.');
  });

  it('offers to open the existing shipment after a 409, looking it up by the echoed pair', async () => {
    const fixture = await setup();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const el = fixture.nativeElement as HTMLElement;
    fill(el, 'customerId', 'cust-1');
    fill(el, 'trackingNumber', 'SP900');
    submit(el);
    const { body, opts } = problem(409, 'duplicate-tracking-number', {
      carrierCode: 'SWIFTPOST',
      trackingNumber: 'SP900',
    });
    http.expectOne('/api/shipments').flush(body, opts);
    await fixture.whenStable();

    const callout = el.querySelector('.pf-callout-warning');
    expect(callout?.textContent).toContain('already registered for SwiftPost');
    const open = Array.from(callout?.querySelectorAll('button') ?? []).find((b) =>
      b.textContent?.includes('Open existing shipment'),
    );
    expect(open).toBeDefined();
    open?.click();

    const lookup = http.expectOne((r) => r.url === '/api/shipments/lookup');
    expect(lookup.request.params.get('carrierCode')).toBe('SWIFTPOST');
    expect(lookup.request.params.get('trackingNumber')).toBe('SP900');
    lookup.flush(shipment({ shipmentId: 'existing-id', retailerId: 'retailer-7' }));
    await fixture.whenStable();
    expect(navigate).toHaveBeenCalledWith(['/shipments', 'existing-id']);
    // Opening someone else's shipment does not switch the selected retailer.
    expect(TestBed.inject(RetailerContext).retailerId()).toBe('retailer-42');
  });

  it('shows a form-level error when the existing shipment cannot be opened', async () => {
    const fixture = await setup();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const el = fixture.nativeElement as HTMLElement;
    fill(el, 'customerId', 'cust-1');
    fill(el, 'trackingNumber', 'SP900');
    submit(el);
    const conflict = problem(409, 'duplicate-tracking-number', { carrierCode: 'SWIFTPOST', trackingNumber: 'SP900' });
    http.expectOne('/api/shipments').flush(conflict.body, conflict.opts);
    await fixture.whenStable();

    Array.from(el.querySelectorAll('button'))
      .find((b) => b.textContent?.includes('Open existing shipment'))
      ?.click();
    const missing = problem(404, 'shipment-not-found', {}, 'corr-gone');
    http.expectOne((r) => r.url === '/api/shipments/lookup').flush(missing.body, missing.opts);
    await fixture.whenStable();
    expect(el.querySelector('pf-error-panel')?.textContent).toContain('corr-gone');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('maps backend validation errors onto their controls', async () => {
    const fixture = await setup();
    const el = fixture.nativeElement as HTMLElement;
    fill(el, 'customerId', 'cust-1');
    fill(el, 'trackingNumber', 'SP900');
    submit(el);
    const { body, opts } = problem(400, 'validation-failed', {
      errors: { customerId: 'size must be between 0 and 64' },
    });
    http.expectOne('/api/shipments').flush(body, opts);
    await fixture.whenStable();
    expect(el.textContent).toContain('size must be between 0 and 64');
    expect(el.querySelector('pf-error-panel')).toBeNull();
  });

  it('shows a form-level error with correlation id for a server failure', async () => {
    const fixture = await setup();
    const el = fixture.nativeElement as HTMLElement;
    fill(el, 'customerId', 'cust-1');
    fill(el, 'trackingNumber', 'SP900');
    submit(el);
    const { body, opts } = problem(500, 'internal-error', {}, 'corr-500');
    http.expectOne('/api/shipments').flush(body, opts);
    await fixture.whenStable();
    expect(el.querySelector('pf-error-panel')?.textContent).toContain('corr-500');
  });

  it('does not offer carriers without an event normalizer', async () => {
    const fixture = await setup();
    const page = fixture.componentInstance as unknown as { isOperational: (c: string) => boolean };
    expect(page.isOperational('SWIFTPOST')).toBe(true);
    expect(page.isOperational('NORDEX')).toBe(false);
    expect(page.isOperational('METROLINK')).toBe(false);
  });
});
