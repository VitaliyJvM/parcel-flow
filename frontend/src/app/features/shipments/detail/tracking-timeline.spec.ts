import { TestBed } from '@angular/core/testing';
import { trackingEvent } from '../../../../testing/http';
import { TrackingTimeline } from './tracking-timeline';

describe('TrackingTimeline', () => {
  async function render() {
    TestBed.configureTestingModule({ imports: [TrackingTimeline] });
    const fixture = TestBed.createComponent(TrackingTimeline);
    fixture.componentRef.setInput('events', [
      trackingEvent({ eventId: 'e1', sequenceNumber: 1, normalizedEventType: 'LABEL_CREATED', carrierEventType: 'SP_CREATED', eventTime: '2026-08-01T08:00:00Z', receivedAt: '2026-08-03T09:00:00Z', correlationId: 'corr-e1' }),
      trackingEvent({ eventId: 'e3', sequenceNumber: 3, normalizedEventType: 'OUT_FOR_DELIVERY', carrierEventType: 'SP_OFD', eventTime: '2026-08-02T08:00:00Z', receivedAt: '2026-08-03T09:00:01Z', correlationId: 'corr-e3' }),
      trackingEvent({ eventId: 'e2', sequenceNumber: 2, normalizedEventType: 'IN_TRANSIT', carrierEventType: 'SP_TRANSIT', eventTime: '2026-08-01T20:00:00Z', receivedAt: '2026-08-03T09:00:02Z', processingStatus: 'SUPERSEDED', correlationId: 'corr-e2', location: 'Calder Junction', description: 'Departed sorting hub' }),
    ]);
    fixture.componentRef.setInput('currentStatus', 'OUT_FOR_DELIVERY');
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  const ids = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('[data-testid="timeline-event"]')).map((li) => li.id.replace('event-', ''));
  const button = (el: HTMLElement, text: string) =>
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes(text));

  it('compact by default: status, processing state, sequence, description and location, no diagnostics', async () => {
    const { el } = await render();
    expect(ids(el)).toEqual(['e1', 'e2', 'e3']);
    expect(el.querySelector('[data-testid="timeline-summary"]')?.textContent).toContain('2 applied');
    expect(el.querySelector('[data-testid="timeline-summary"]')?.textContent).toContain('1 superseded');
    const superseded = el.querySelector('[data-processing-status="SUPERSEDED"]');
    expect(superseded?.textContent).toContain('Superseded');
    expect(superseded?.textContent).toContain('#2');
    expect(superseded?.textContent).toContain('Departed sorting hub');
    expect(superseded?.textContent).toContain('Calder Junction');
    // Diagnostics are not rendered until expanded.
    expect(superseded?.textContent).not.toContain('corr-e2');
    expect(superseded?.textContent).not.toContain('SP_TRANSIT');
    expect(el.querySelector('#event-e3')?.textContent).toContain('Current status event');
    expect(el.textContent).not.toContain('Set current status');
  });

  it('expands one event via an accessible disclosure button', async () => {
    const { fixture, el } = await render();
    const toggle = el.querySelector<HTMLButtonElement>('#event-e2 .pf-details-toggle');
    expect(toggle?.getAttribute('aria-expanded')).toBe('false');
    expect(toggle?.getAttribute('aria-controls')).toBe('event-e2-details');
    expect(toggle?.textContent).toContain('for event #2');

    toggle?.click();
    await fixture.whenStable();
    expect(toggle?.getAttribute('aria-expanded')).toBe('true');
    const details = el.querySelector('#event-e2-details');
    expect(details?.textContent).toContain('corr-e2');
    expect(details?.textContent).toContain('SP_TRANSIT');
    expect(details?.textContent).toContain('Received by ParcelFlow');
    expect(details?.textContent).toContain('e2');
    // Only that event opened.
    expect(el.querySelector('#event-e1-details')).toBeNull();

    toggle?.click();
    await fixture.whenStable();
    expect(el.querySelector('#event-e2-details')).toBeNull();
  });

  it('detailed mode shows every diagnostic field for every event', async () => {
    const { fixture, el } = await render();
    button(el, 'Detailed')?.click();
    await fixture.whenStable();
    for (const id of ['e1', 'e2', 'e3']) {
      const details = el.querySelector(`#event-${id}-details`);
      expect(details?.textContent).toContain(`corr-${id}`);
      expect(details?.textContent).toContain('Event ID');
    }
    expect(el.querySelectorAll('.pf-details-toggle')).toHaveLength(0);
  });

  it('switches to arrival order on toggle', async () => {
    const { fixture, el } = await render();
    button(el, 'Arrival order')?.click();
    await fixture.whenStable();
    expect(ids(el)).toEqual(['e1', 'e3', 'e2']);
  });
});
