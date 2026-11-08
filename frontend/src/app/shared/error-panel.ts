import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ApiError } from '../core/api/api-error';
import { CopyButton } from './copy-button';

/**
 * Renders an {@link ApiError}: what went wrong, what the user can do, and the correlation id to
 * quote when looking for the request in the service logs.
 */
@Component({
  selector: 'pf-error-panel',
  imports: [MatButtonModule, MatIconModule, CopyButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="pf-callout pf-callout-error" role="alert">
      <mat-icon aria-hidden="true">{{ error().isNetworkError ? 'cloud_off' : 'error' }}</mat-icon>
      <div class="pf-callout-body">
        <strong>{{ heading() }}</strong>
        <p>{{ error().detail }}</p>
        @if (fieldList().length > 0) {
          <ul>
            @for (entry of fieldList(); track entry[0]) {
              <li>
                <code>{{ entry[0] }}</code
                >: {{ entry[1] }}
              </li>
            }
          </ul>
        }
        @if (error().correlationId; as id) {
          <p class="pf-muted pf-correlation">
            Correlation ID <code>{{ id }}</code>
            <pf-copy-button [value]="id" label="Copy correlation ID" />
          </p>
        }
      </div>
      @if (retryable()) {
        <button mat-stroked-button type="button" (click)="retry.emit()">Try again</button>
      }
    </div>
  `,
})
export class ErrorPanel {
  readonly error = input.required<ApiError>();
  /** Optional context, e.g. "Could not load tracking history". */
  readonly context = input<string | null>(null);
  readonly retryable = input(false);
  readonly retry = output<void>();

  protected readonly heading = computed(() => {
    const e = this.error();
    const status = e.status > 0 ? ` (HTTP ${e.status})` : '';
    return this.context() ? `${this.context()}: ${e.title}${status}` : `${e.title}${status}`;
  });

  protected readonly fieldList = computed(() => Object.entries(this.error().fieldErrors));
}
