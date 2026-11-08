import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RetailerDialogLauncher } from '../core/retailer/retailer-dialog';

/** Shown in place of retailer-scoped content until a retailer is selected. */
@Component({
  selector: 'pf-retailer-required',
  imports: [MatButtonModule, MatIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="pf-empty">
      <mat-icon aria-hidden="true">storefront</mat-icon>
      <h2>Choose a retailer</h2>
      <p>
        Shipment lists are scoped to one retailer ID, because that is how the API exposes them
        (<code>GET /api/retailers/&#123;retailerId&#125;/shipments</code>). There is no endpoint
        listing retailers, so type the ID used when registering shipments.
      </p>
      <button mat-flat-button type="button" (click)="void launcher.open()">Select retailer</button>
    </div>
  `,
})
export class RetailerRequired {
  protected readonly launcher = inject(RetailerDialogLauncher);
}
