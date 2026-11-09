import { HttpTestingController, TestRequest } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { countPage, page, problem, provideTestHttp, shipment } from '../../../testing/http';
import { signInAs } from '../../../testing/auth';
import { RetailerContext } from '../../core/retailer/retailer-context';
import { DashboardPage } from './dashboard-page';

describe('DashboardPage', () => {
  let http: HttpTestingController;

  async function setup() {
    TestBed.configureTestingModule({
      imports: [DashboardPage],
      providers: [...provideTestHttp(), provideRouter([])],
    });
    localStorage.clear();
    signInAs('ADMIN');
    TestBed.inject(RetailerContext).select('retailer-42');
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(DashboardPage);
    TestBed.tick();
    return fixture;
  }

  /** Answers every pending request until none are left (counts load 3 at a time). */
  async function answerAll(
    fixture: { whenStable(): Promise<unknown> },
    failStatus?: string,
    failRecent = false,
  ) {
    for (let round = 0; round < 10; round++) {
      const pending: TestRequest[] = http.match(() => true);
      if (pending.length === 0) break;
      for (const req of pending) {
        const status = req.request.params.get('status');
        const size = req.request.params.get('size');
        if (req.request.url.startsWith('/api/admin')) {
          req.flush(countPage(status === 'FAILED' ? 2 : 0));
        } else if (status === failStatus) {
          const { body, opts } = problem(500, 'internal-error');
          req.flush(body, opts);
        } else if (size === '5' && failRecent) {
          // 500: not retried by the GET retry interceptor (that covers 0/502/503/504 only).
          const { body, opts } = problem(500, 'internal-error');
          req.flush(body, opts);
        } else if (size === '5') {
          req.flush(page([shipment({ currentStatus: 'DELIVERED' })], { totalElements: 3 }));
        } else {
          req.flush(countPage(status === 'DELIVERED' ? 2 : status === 'IN_TRANSIT' ? 1 : 0));
        }
      }
      await fixture.whenStable();
    }
  }

  afterEach(() => http.verify());

  it('renders a total, eight status tiles with muted zeros, and a labelled distribution legend', async () => {
    const fixture = await setup();
    await answerAll(fixture);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.pf-tile-total')?.textContent).toContain('3');
    const tiles = el.querySelectorAll('.pf-tiles .pf-tile');
    expect(tiles).toHaveLength(8);
    expect(el.querySelectorAll('.pf-tiles .pf-tile-zero')).toHaveLength(6);
    // Zero tiles remain links to the filtered list.
    expect(el.querySelector('.pf-tiles .pf-tile-zero')?.getAttribute('href')).toContain('/shipments?status=');

    const legend = Array.from(el.querySelectorAll('.pf-legend li')).map((li) => li.textContent?.replace(/\s+/g, ' ').trim());
    expect(legend).toEqual(['In transit 1 · 33%', 'Delivered 2 · 67%']);
    expect(el.querySelector('.pf-bar')?.getAttribute('aria-label')).toBe(
      'Status distribution: In transit 1 (33%), Delivered 2 (67%)',
    );
  });

  it('moves the counting explanation into an accessible info tooltip', async () => {
    const fixture = await setup();
    await answerAll(fixture);
    const el = fixture.nativeElement as HTMLElement;
    const info = el.querySelector('button[aria-label="How these counts are computed"]');
    expect(info).not.toBeNull();
    expect(el.textContent).not.toContain('eight filtered list requests');
    expect(el.querySelector('#failed-heading')?.parentElement?.textContent).toContain('All retailers');
  });

  it('marks only the failed tile on a partial failure', async () => {
    const fixture = await setup();
    await answerAll(fixture, 'DELAYED');
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('.pf-tile-error')).toHaveLength(1);
    expect(el.querySelector('.pf-tile-error')?.getAttribute('aria-label')).toBe('Delayed: —');
    expect(el.textContent).toContain('Some counts could not be loaded');
  });

  it('shows an error state, not a permanent "…", when the total-count request fails', async () => {
    const fixture = await setup();
    await answerAll(fixture, undefined, true);
    const el = fixture.nativeElement as HTMLElement;
    const total = el.querySelector('[data-testid="total-tile"]');
    expect(total?.classList).toContain('pf-tile-error');
    expect(total?.textContent).toContain('—');
    expect(total?.textContent).toContain('unavailable');
    expect(total?.textContent).not.toContain('…');
    expect(total?.getAttribute('aria-label')).toBe('All shipments: —');
    // Independent partial failure: the status counts still loaded.
    expect(el.querySelectorAll('.pf-tiles .pf-tile-error')).toHaveLength(0);
    expect(el.querySelector('.pf-legend')?.textContent).toContain('Delivered');
    // And the Recent card reports the failure with its correlation id.
    expect(el.querySelector('pf-error-panel')?.textContent).toContain('Could not load shipments');
  });
});

describe('DashboardPage (RETAILER)', () => {
  it('never asks for failed events, which are ADMIN-only and span all retailers', async () => {
    TestBed.configureTestingModule({
      imports: [DashboardPage],
      providers: [...provideTestHttp(), provideRouter([])],
    });
    localStorage.clear();
    signInAs('RETAILER', 'alpha-goods');
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(DashboardPage);
    TestBed.tick();
    for (let round = 0; round < 10; round++) {
      const pending = http.match(() => true);
      if (pending.length === 0) break;
      for (const req of pending) {
        expect(req.request.url).toBe('/api/retailers/alpha-goods/shipments');
        req.flush(req.request.params.get('size') === '5' ? page([]) : countPage(0));
      }
      await fixture.whenStable();
    }
    expect((fixture.nativeElement as HTMLElement).querySelector('#failed-heading')).toBeNull();
    http.verify();
  });
});
