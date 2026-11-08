import { ChangeDetectionStrategy, Component, computed, effect, input } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';
import type { CarrierCode } from '../../core/api/models';
import { CopyButton } from '../../shared/copy-button';
import { CARRIER_META } from '../../shared/domain';
import { notBlank } from '../../shared/validators';
import { JAVA_LONG_MAX, JAVA_LONG_MIN, javaLongValidator } from './java-long';
import {
  buildSimulatorCommand,
  NORMAL_SCENARIO,
  SCENARIOS,
  SIMULATOR_CARRIERS,
  SimulatorScenario,
} from './simulator-command';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Builds a copy-ready `docker compose run carrier-simulator ...` command.
 *
 * Deliberately CLI-only: the simulator publishes straight to Kafka and has no HTTP interface, and
 * adding one would put an unauthenticated "write arbitrary events" endpoint next to an already
 * unauthenticated API. The browser never executes anything.
 */
@Component({
  selector: 'pf-simulation-panel',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatIconModule,
    CopyButton,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './simulation-panel.html',
  styleUrl: './simulation-panel.scss',
})
export class SimulationPanel {
  readonly shipmentId = input<string | null>(null);
  readonly trackingNumber = input<string | null>(null);
  readonly carrier = input<CarrierCode | null>(null);
  /** When prefilled from a shipment, identity fields are read-only. */
  readonly locked = input(false);

  protected readonly scenarios = SCENARIOS;
  protected readonly longMin = JAVA_LONG_MIN.toString();
  protected readonly longMax = JAVA_LONG_MAX.toString();
  protected readonly carriers = SIMULATOR_CARRIERS;
  protected readonly carrierMeta = CARRIER_META;

  protected readonly form = new FormGroup({
    shipmentId: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.pattern(UUID_PATTERN)],
    }),
    trackingNumber: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, notBlank, Validators.maxLength(128)],
    }),
    carrier: new FormControl<CarrierCode>('SWIFTPOST', { nonNullable: true }),
    scenario: new FormControl<SimulatorScenario>('NORMAL', { nonNullable: true }),
    // Strings, not numbers: see java-long.ts. Bounds follow the CLI (Long.parseLong; delay >= 0).
    delayMs: new FormControl('', { nonNullable: true, validators: [javaLongValidator({ nonNegative: true })] }),
    seed: new FormControl('', { nonNullable: true, validators: [javaLongValidator()] }),
  });

  private readonly value = toSignal(this.form.valueChanges, { initialValue: this.form.value });
  private readonly status = toSignal(this.form.statusChanges, { initialValue: this.form.status });

  protected readonly unsupportedCarrier = computed(() => {
    const carrier = this.carrier();
    return carrier !== null && !SIMULATOR_CARRIERS.includes(carrier);
  });

  protected readonly selectedScenario = computed(
    () => SCENARIOS.find((s) => s.id === this.value().scenario) ?? NORMAL_SCENARIO,
  );

  protected readonly command = computed(() => {
    // Track value changes; read the raw value so disabled (locked) identity fields are included.
    this.value();
    if (this.status() !== 'VALID') {
      return null;
    }
    const v = this.form.getRawValue();
    // The validators already guarantee valid input; the builder throws rather than emit a wrong
    // command, so a disagreement between the two can only hide the command, never corrupt it.
    try {
      return buildSimulatorCommand({
        shipmentId: v.shipmentId,
        trackingNumber: v.trackingNumber,
        carrier: v.carrier,
        scenario: v.scenario,
        delayMs: v.delayMs,
        seed: v.seed,
      });
    } catch {
      return null;
    }
  });

  constructor() {
    effect(() => {
      const shipmentId = this.shipmentId();
      const trackingNumber = this.trackingNumber();
      const carrier = this.carrier();
      this.form.patchValue({
        ...(shipmentId ? { shipmentId } : {}),
        ...(trackingNumber ? { trackingNumber } : {}),
        ...(carrier && SIMULATOR_CARRIERS.includes(carrier) ? { carrier } : {}),
      });
      const identity = [this.form.controls.shipmentId, this.form.controls.trackingNumber, this.form.controls.carrier];
      for (const control of identity) {
        if (this.locked()) {
          control.disable({ emitEvent: false });
        } else {
          control.enable({ emitEvent: false });
        }
      }
      this.form.updateValueAndValidity();
    });
  }
}
