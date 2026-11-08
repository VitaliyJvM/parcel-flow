import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { page, problem, provideTestHttp, shipment } from '../../../testing/http';
import { RetailerContext } from '../../core/retailer/retailer-context';
import { parseListQuery } from './list-query';
import { ShipmentListPage } from './shipment-list-page';

describe('parseListQuery', () => {
  it('accepts valid values', () => {
    expect(parseListQuery('DELIVERED', '3', '50', 100)).toEqual({ status: 'DELIVERED', page: 3, size: 50 });
  });

  it('clamps hand-edited URLs to what the backend accepts', () => {
    expect(parseListQuery('BOGUS', '-1', '5000', 100)).toEqual({ status: null, page: 0, size: 100 });
    expect(parseListQuery(undefined, 'x', '0', 100)).toEqual({ status: null, page: 0, size: 20 });
  });
});

describe('ShipmentListPage', () => {
  let http: HttpTestingController;

  async function setup(query: { status?: string; page?: string; size?: string } = {}) {
    TestBed.configureTestingModule({
      imports: [ShipmentListPage],
      providers: [...provideTestHttp(), provideRouter([])],
    });
    localStorage.clear();
    TestBed.inject(RetailerContext).select('retailer-42');
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ShipmentListPage);
    for (const [k, v] of Object.entries(query)) fixture.componentRef.setInput(k, v);
    // Not whenStable(): a pending rxResource request keeps the fixture unstable until flushed.
    TestBed.tick();
    return fixture;
  }

  afterEach(() => http.verify());

  it('requests the page and filter from the URL and renders rows', async () => {
    const fixture = await setup({ status: 'IN_TRANSIT', page: '1', size: '10' });
    const req = http.expectOne((r) => r.url === '/api/retailers/retailer-42/shipments');
    expect(req.request.params.get('status')).toBe('IN_TRANSIT');
    expect(req.request.params.get('page')).toBe('1');
    expect(req.request.params.get('size')).toBe('10');
    req.flush(
      page([shipment({ currentStatus: 'IN_TRANSIT' })], { page: 1, size: 10, totalElements: 11, totalPages: 2 }),
    );
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('tr[mat-row]')).toHaveLength(1);
    expect(el.textContent).toContain('SP100000000042');
    expect(el.textContent).toContain('In transit');
  });

  it('writes filter and paging changes to the URL', async () => {
    const fixture = await setup();
    http.expectOne((r) => r.url === '/api/retailers/retailer-42/shipments').flush(page([shipment()]));
    await fixture.whenStable();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const component = fixture.componentInstance as unknown as {
      filterBy(s: string | null): void;
      onPage(e: { pageIndex: number; pageSize: number }): void;
    };
    component.filterBy('DELIVERED');
    expect(navigate).toHaveBeenLastCalledWith([], {
      queryParams: { status: 'DELIVERED', page: null },
      queryParamsHandling: 'merge',
    });
    component.onPage({ pageIndex: 2, pageSize: 50 });
    expect(navigate).toHaveBeenLastCalledWith([], {
      queryParams: { page: 2, size: 50 },
      queryParamsHandling: 'merge',
    });
  });

  it('shows "All statuses" in the filter when no status is selected', async () => {
    const fixture = await setup();
    http.expectOne((r) => r.url === '/api/retailers/retailer-42/shipments').flush(page([shipment()]));
    await fixture.whenStable();
    const trigger = (fixture.nativeElement as HTMLElement).querySelector('.pf-status-filter .mat-mdc-select-value');
    expect(trigger?.textContent?.trim()).toBe('All statuses');
  });

  it('renders short timestamps with the full value available to assistive technology', async () => {
    const fixture = await setup();
    http
      .expectOne((r) => r.url === '/api/retailers/retailer-42/shipments')
      .flush(page([shipment({ createdAt: '2026-08-01T17:05:09Z' })]));
    await fixture.whenStable();
    const time = (fixture.nativeElement as HTMLElement).querySelector('td.mat-column-created time');
    expect(time?.getAttribute('datetime')).toBe('2026-08-01T17:05:09Z');
    expect(time?.querySelector('[aria-hidden="true"]')?.textContent).not.toContain(':09');
    expect(time?.querySelector('.pf-visually-hidden')?.textContent).toContain(':09');
  });

  it('treats a page beyond the last one as out of range, not as "no shipments"', async () => {
    const fixture = await setup({ page: '50', size: '20' });
    http
      .expectOne((r) => r.url === '/api/retailers/retailer-42/shipments' && r.params.get('page') === '50')
      .flush(page([], { page: 50, size: 20, totalElements: 45, totalPages: 3, hasNext: false }));
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const state = el.querySelector('[data-testid="page-out-of-range"]');
    expect(state?.textContent).toContain('Page 51 is out of range');
    expect(state?.textContent).toContain('There are 45 shipments on 3 pages.');
    expect(el.textContent).not.toContain('No shipments yet');

    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const button = (text: string) => Array.from(state?.querySelectorAll('button') ?? []).find((b) => b.textContent?.includes(text));
    button('Go to last page (3)')?.click();
    expect(navigate).toHaveBeenLastCalledWith([], { queryParams: { page: 2 }, queryParamsHandling: 'merge' });
    button('First page')?.click();
    // Page 0 is written as "no parameter", so the URL stays canonical.
    expect(navigate).toHaveBeenLastCalledWith([], { queryParams: { page: null }, queryParamsHandling: 'merge' });
  });

  it('still shows the genuine empty state when the retailer has no shipments', async () => {
    const fixture = await setup({ page: '3' });
    http
      .expectOne((r) => r.url === '/api/retailers/retailer-42/shipments')
      .flush(page([], { page: 3, totalElements: 0, totalPages: 0 }));
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="page-out-of-range"]')).toBeNull();
    expect(el.textContent).toContain('No shipments yet');
  });

  it('shows a filter-specific empty state', async () => {
    const fixture = await setup({ status: 'DELAYED' });
    http.expectOne((r) => r.url === '/api/retailers/retailer-42/shipments').flush(page([]));
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No shipments with status “Delayed”');
  });

  it('shows an error panel with retry', async () => {
    const fixture = await setup();
    const { body, opts } = problem(500, 'internal-error', {}, 'corr-x');
    http.expectOne((r) => r.url === '/api/retailers/retailer-42/shipments').flush(body, opts);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('pf-error-panel')?.textContent).toContain('corr-x');
    const retry = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Try again'));
    retry?.click();
    TestBed.tick();
    http.expectOne((r) => r.url === '/api/retailers/retailer-42/shipments').flush(page([]));
  });

  it('asks for a retailer when none is selected', async () => {
    TestBed.configureTestingModule({
      imports: [ShipmentListPage],
      providers: [...provideTestHttp(), provideRouter([])],
    });
    localStorage.clear();
    TestBed.inject(RetailerContext).clear();
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ShipmentListPage);
    TestBed.tick();
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Choose a retailer');
    http.expectNone((r) => r.url.includes('/shipments'));
  });
});
