import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { TestBed } from '@angular/core/testing';
import { MatSelectHarness } from '@angular/material/select/testing';
import { provideRouter } from '@angular/router';
import { buildSimulatorCommand, SCENARIOS, shellQuote } from './simulator-command';
import { SimulationPanel } from './simulation-panel';

describe('simulator command', () => {
  it('builds the documented docker compose invocation', () => {
    expect(
      buildSimulatorCommand({
        shipmentId: '00c5356b-8b0b-47e5-b88c-1e504dd2bf34',
        trackingNumber: 'SP100000000042',
        carrier: 'SWIFTPOST',
        scenario: 'OUT_OF_ORDER',
      }),
    ).toBe(
      'docker compose run --rm carrier-simulator \\\n' +
        '  --shipment-id=00c5356b-8b0b-47e5-b88c-1e504dd2bf34 \\\n' +
        '  --tracking-number=SP100000000042 \\\n' +
        '  --carrier=SWIFTPOST \\\n' +
        '  --scenario=OUT_OF_ORDER',
    );
  });

  const base = { shipmentId: 'id', trackingNumber: 'T', carrier: 'PACIFICA', scenario: 'NORMAL' } as const;

  it('adds optional delay and seed', () => {
    const cmd = buildSimulatorCommand({ ...base, delayMs: '100', seed: '42' });
    expect(cmd).toContain('--delay-ms=100');
    expect(cmd).toContain('--seed=42');
  });

  it('omits blank optional values (CLI defaults apply)', () => {
    const cmd = buildSimulatorCommand({ ...base, delayMs: ' ', seed: '' });
    expect(cmd).not.toContain('--delay-ms');
    expect(cmd).not.toContain('--seed');
  });

  it('carries seeds exactly, including the long boundaries and unsafe integers', () => {
    expect(buildSimulatorCommand({ ...base, seed: '9223372036854775807' })).toContain('--seed=9223372036854775807');
    expect(buildSimulatorCommand({ ...base, seed: '-9223372036854775808' })).toContain('--seed=-9223372036854775808');
    expect(buildSimulatorCommand({ ...base, seed: '9007199254740993' })).toContain('--seed=9007199254740993');
    // Canonical decimal form, the value Long.parseLong would produce.
    expect(buildSimulatorCommand({ ...base, seed: ' +007 ' })).toContain('--seed=7');
  });

  it('refuses to build a command the CLI would reject or misread', () => {
    for (const seed of ['9223372036854775808', '1e21', '12.5', 'abc']) {
      expect(() => buildSimulatorCommand({ ...base, seed }), seed).toThrow(RangeError);
    }
    for (const delayMs of ['-1', '1.5', '1e3', '9223372036854775808']) {
      expect(() => buildSimulatorCommand({ ...base, delayMs }), delayMs).toThrow(RangeError);
    }
    expect(buildSimulatorCommand({ ...base, delayMs: '0' })).toContain('--delay-ms=0');
  });

  it('shell-quotes free-text values so a pasted command cannot run anything else', () => {
    expect(shellQuote('SP-1_2.3')).toBe('SP-1_2.3');
    expect(shellQuote('a b')).toBe("'a b'");
    expect(shellQuote("x'; rm -rf / #")).toBe(`'x'\\''; rm -rf / #'`);
    expect(shellQuote('$(id)')).toBe("'$(id)'");
  });

  it('describes all six simulator scenarios', () => {
    expect(SCENARIOS.map((s) => s.id)).toEqual([
      'NORMAL',
      'DUPLICATE',
      'OUT_OF_ORDER',
      'INVALID_EVENT',
      'UNKNOWN_CARRIER_EVENT',
      'RAPID_CONCURRENT_EVENTS',
    ]);
  });
});

describe('SimulationPanel', () => {
  it('prefills from the shipment and regenerates the command on scenario change', async () => {
    TestBed.configureTestingModule({ imports: [SimulationPanel], providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(SimulationPanel);
    fixture.componentRef.setInput('shipmentId', '00c5356b-8b0b-47e5-b88c-1e504dd2bf34');
    fixture.componentRef.setInput('trackingNumber', 'PC1');
    fixture.componentRef.setInput('carrier', 'PACIFICA');
    fixture.componentRef.setInput('locked', true);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const command = () => el.querySelector('[data-testid="simulator-command"]')?.textContent ?? '';
    expect(command()).toContain('--carrier=PACIFICA');
    expect(command()).toContain('--scenario=NORMAL');
    expect(el.querySelector<HTMLInputElement>('input[formcontrolname="shipmentId"]')?.disabled).toBe(true);

    const scenario = await TestbedHarnessEnvironment.loader(fixture).getHarness(
      MatSelectHarness.with({ selector: '.pf-scenario-field mat-select' }),
    );
    expect(await scenario.getValueText()).toBe('NORMAL');
    await scenario.open();
    expect(await scenario.getOptions()).toHaveLength(6);
    await scenario.clickOptions({ text: 'DUPLICATE' });
    await fixture.whenStable();
    expect(command()).toContain('--scenario=DUPLICATE');
    expect(el.querySelector('[data-testid="scenario-explanation"]')?.textContent).toContain('idempotency');
  });

  it('explains the corrected UNKNOWN_CARRIER_EVENT and RAPID_CONCURRENT_EVENTS semantics', async () => {
    TestBed.configureTestingModule({ imports: [SimulationPanel], providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(SimulationPanel);
    await fixture.whenStable();
    const explanation = () =>
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="scenario-explanation"]')?.textContent ?? '';
    const scenario = await TestbedHarnessEnvironment.loader(fixture).getHarness(MatSelectHarness);
    await scenario.clickOptions({ text: 'UNKNOWN_CARRIER_EVENT' });
    await fixture.whenStable();
    expect(explanation()).toContain('supported carrier');
    expect(explanation()).toContain('UNKNOWN_EVENT_TYPE (not UNSUPPORTED_CARRIER)');

    await scenario.clickOptions({ text: 'RAPID_CONCURRENT_EVENTS' });
    await fixture.whenStable();
    expect(explanation()).toContain('does not prove simultaneous processing of the same shipment');
  });

  it('shows field errors and no command for invalid seed or delay', async () => {
    TestBed.configureTestingModule({ imports: [SimulationPanel], providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(SimulationPanel);
    fixture.componentRef.setInput('shipmentId', '00c5356b-8b0b-47e5-b88c-1e504dd2bf34');
    fixture.componentRef.setInput('trackingNumber', 'SP1');
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const type = async (name: string, value: string) => {
      const input = el.querySelector<HTMLInputElement>(`input[formcontrolname="${name}"]`);
      if (!input) throw new Error(name);
      input.value = value;
      input.dispatchEvent(new Event('input'));
      input.dispatchEvent(new Event('blur'));
      await fixture.whenStable();
    };
    const command = () => el.querySelector('[data-testid="simulator-command"]')?.textContent ?? null;

    await type('seed', '1e21');
    expect(command()).toBeNull();
    expect(el.querySelector('[data-testid="simulator-command-missing"]')).not.toBeNull();
    expect(el.textContent).toContain('Whole number, e.g. 42 (no decimals or exponents).');

    await type('seed', '9223372036854775808');
    expect(command()).toBeNull();
    expect(el.textContent).toContain('Must be a Java long: -9223372036854775808 to 9223372036854775807.');

    await type('seed', '9223372036854775807');
    expect(command()).toContain('--seed=9223372036854775807');

    await type('delayMs', '-1');
    expect(command()).toBeNull();
    expect(el.textContent).toContain('Must be between 0 and 9223372036854775807.');

    await type('delayMs', '250');
    expect(command()).toContain('--delay-ms=250');
    expect(el.querySelector<HTMLInputElement>('input[formcontrolname="seed"]')?.type).toBe('text');
  });

  it('warns for carriers the simulator cannot drive', async () => {
    TestBed.configureTestingModule({ imports: [SimulationPanel], providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(SimulationPanel);
    fixture.componentRef.setInput('carrier', 'NORDEX');
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('SWIFTPOST and PACIFICA only');
    expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid="simulator-command"]')).toBeNull();
  });
});
