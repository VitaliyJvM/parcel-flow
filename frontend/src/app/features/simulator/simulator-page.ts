import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { CarrierCode } from '../../core/api/models';
import { CARRIERS } from '../../shared/domain';
import { SimulationPanel } from './simulation-panel';

@Component({
  selector: 'pf-simulator-page',
  imports: [SimulationPanel],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="pf-page-header">
      <div>
        <h1 class="pf-title">Carrier simulator</h1>
        <p class="pf-muted">
          The simulator is a command-line tool that publishes a carrier's own event codes to Kafka.
          This page only builds the command; open a shipment to prefill it.
        </p>
      </div>
    </header>
    <section class="pf-card">
      <pf-simulation-panel
        [shipmentId]="shipmentId() ?? null"
        [trackingNumber]="trackingNumber() ?? null"
        [carrier]="carrierCode()"
      />
    </section>
  `,
})
export class SimulatorPage {
  // Optional query parameters for deep links.
  readonly shipmentId = input<string | undefined>();
  readonly trackingNumber = input<string | undefined>();
  readonly carrier = input<string | undefined>();

  protected readonly carrierCode = computed<CarrierCode | null>(
    () => CARRIERS.find((c) => c === this.carrier()) ?? null,
  );
}
