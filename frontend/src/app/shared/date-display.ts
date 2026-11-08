import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatTooltipModule } from '@angular/material/tooltip';
import { formatInstant, formatInstantShort, formatInstantUtc, formatLocalDate } from './dates';

/**
 * An ISO instant in the viewer's zone.
 *
 * `full` (default) shows seconds and the zone name. `short` shows "Nov 7, 6:10 PM" for dense
 * tables; the full local value and the exact UTC value are then in the tooltip, and the full value
 * is what assistive technology reads (the short text is hidden from it), so nothing is lost.
 */
@Component({
  selector: 'pf-instant',
  imports: [MatTooltipModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (value()) {
      @if (format() === 'short') {
        <time [attr.datetime]="utc()" class="pf-nowrap">
          <span aria-hidden="true" [matTooltip]="local() + ' · ' + utc() + ' UTC'">{{ short() }}</span>
          <span class="pf-visually-hidden">{{ local() }}</span>
        </time>
      } @else {
        <time [attr.datetime]="utc()" [matTooltip]="utc() + ' (UTC)'">{{ local() }}</time>
      }
    } @else {
      <span class="pf-muted">{{ placeholder() }}</span>
    }
  `,
})
export class InstantDisplay {
  readonly value = input<string | null | undefined>(null);
  readonly placeholder = input('—');
  readonly format = input<'full' | 'short'>('full');
  protected readonly local = computed(() => formatInstant(this.value()));
  protected readonly short = computed(() => formatInstantShort(this.value()));
  protected readonly utc = computed(() => formatInstantUtc(this.value()));
}

/** A LocalDate (no time zone): formatted from its components so it never shifts by a day. */
@Component({
  selector: 'pf-local-date',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (value()) {
      <time [attr.datetime]="value()">{{ formatted() }}</time>
    } @else {
      <span class="pf-muted">{{ placeholder() }}</span>
    }
  `,
})
export class LocalDateDisplay {
  readonly value = input<string | null | undefined>(null);
  readonly placeholder = input('Not provided');
  protected readonly formatted = computed(() => formatLocalDate(this.value()));
}
