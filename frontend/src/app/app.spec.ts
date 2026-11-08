import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideTestHttp } from '../testing/http';
import { App } from './app';

describe('App shell', () => {
  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [...provideTestHttp(), provideRouter([])],
    }).compileComponents();
  });

  it('renders navigation and the no-authentication notice', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const labels = Array.from(el.querySelectorAll('mat-nav-list a')).map((a) => a.textContent?.trim());
    expect(labels).toEqual([
      'space_dashboardOverview',
      'inventory_2Shipments',
      'add_boxRegister shipment',
      'reportFailed events',
      'terminalCarrier simulator',
      'infoAbout this demo',
    ]);
    expect(el.querySelector('.pf-demo-banner')?.textContent).toContain('no authentication');
    expect(el.textContent).toContain('Select retailer');
  });
});
