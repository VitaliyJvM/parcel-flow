import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import type { ShipmentStatus } from '../core/api/models';
import { SHIPMENT_STATUS_META, StatusTone } from './domain';

/** One visual language for shipment status everywhere: icon + text, never colour alone. */
@Component({
  selector: 'pf-status-chip',
  imports: [MatIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="pf-tag" [class]="'pf-tag pf-tone-' + meta().tone">
      <mat-icon aria-hidden="true">{{ meta().icon }}</mat-icon>
      <span>{{ meta().label }}</span>
    </span>
  `,
})
export class StatusChip {
  readonly status = input.required<ShipmentStatus>();
  protected readonly meta = computed(() => SHIPMENT_STATUS_META[this.status()]);
}

/** Small neutral/toned tag for non-shipment enums (processing status, failed-event status). */
@Component({
  selector: 'pf-tag',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span [class]="'pf-tag pf-tone-' + tone()"><ng-content /></span>`,
})
export class Tag {
  readonly tone = input<StatusTone>('neutral');
}
