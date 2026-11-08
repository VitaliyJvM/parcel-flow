import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { filter, finalize, switchMap } from 'rxjs';
import { ApiError, toApiError } from '../../core/api/api-error';
import { FailedEventResponse, FailedEventRetryResponse } from '../../core/api/models';
import { FailedEventsApi } from '../../core/api/tracking-api';
import { ConfirmDialog, ConfirmDialogData } from '../../shared/confirm-dialog';
import { CopyButton } from '../../shared/copy-button';
import { InstantDisplay } from '../../shared/date-display';
import { ERROR_CATEGORY_META, FAILED_EVENT_STATUS_META, SHIPMENT_STATUS_META } from '../../shared/domain';
import { ErrorPanel } from '../../shared/error-panel';
import { Tag } from '../../shared/status-chip';

export type RetryOutcome =
  | { kind: 'succeeded'; response: FailedEventRetryResponse }
  | { kind: 'failed-again'; response: FailedEventRetryResponse }
  | { kind: 'rejected'; error: ApiError; message: string };

/** Turns a retry call's result into what the operator needs to read. 200 ≠ success. */
export function interpretRetry(result: FailedEventRetryResponse | ApiError): RetryOutcome {
  if (result instanceof ApiError) {
    let message: string;
    if (result.status === 409 && result.slug === 'failed-event-not-retryable') {
      message = `This failure category (${result.extension('errorCategory') ?? 'unknown'}) can never succeed on retry.`;
    } else if (result.status === 409 && result.slug === 'failed-event-retry-not-available') {
      message = `Retry not available: the record is ${result.extension('status') ?? 'not in FAILED state'} (another retry holds it, or it is already resolved).`;
    } else if (result.status === 404) {
      message = 'This failed event no longer exists.';
    } else if (result.status === 400) {
      message = 'The request was malformed.';
    } else if (result.isNetworkError) {
      message = 'No response from the service. The retry may or may not have run — reload to check.';
    } else {
      message = 'The service failed while retrying. Reload to see the current state.';
    }
    return { kind: 'rejected', error: result, message };
  }
  return result.succeeded
    ? { kind: 'succeeded', response: result }
    : { kind: 'failed-again', response: result };
}

/** Manual retry is offered only when the backend says the category allows it AND the record is FAILED. */
export function canRetry(event: FailedEventResponse): boolean {
  return event.retryableManually && event.status === 'FAILED';
}

@Component({
  selector: 'pf-failed-event-detail-page',
  imports: [RouterLink, MatButtonModule, MatIconModule, MatProgressBarModule, InstantDisplay, ErrorPanel, Tag, CopyButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './failed-event-detail-page.html',
  styles: `
    .pf-status-line {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
    }
    .pf-span-2,
    .pf-wide {
      grid-column: span 2;
    }
    @media (max-width: 599.98px) {
      .pf-span-2,
      .pf-wide {
        grid-column: auto;
      }
    }
  `,
})
export class FailedEventDetailPage {
  private readonly api = inject(FailedEventsApi);
  private readonly dialog = inject(MatDialog);

  readonly failedEventId = input.required<string>();

  protected readonly categoryMeta = ERROR_CATEGORY_META;
  protected readonly statusMeta = FAILED_EVENT_STATUS_META;
  protected readonly shipmentStatusMeta = SHIPMENT_STATUS_META;

  protected readonly event = rxResource({
    params: () => this.failedEventId(),
    stream: ({ params }) => this.api.get(params),
  });
  protected readonly loadError = computed(() => {
    const e = this.event.error();
    return e ? toApiError(e) : null;
  });
  protected readonly retryAllowed = computed(() => {
    const e = this.event.hasValue() ? this.event.value() : null;
    return e ? canRetry(e) : false;
  });

  protected readonly retrying = signal(false);
  protected readonly outcome = signal<RetryOutcome | null>(null);

  protected confirmRetry(failed: FailedEventResponse): void {
    const data: ConfirmDialogData = {
      title: 'Reprocess this event?',
      message:
        'The stored payload is run through the normal processing pipeline, in-process. If it ' +
        'succeeds, the shipment may change status and notification records may be created.',
      details: [
        `Category: ${failed.errorCategory}`,
        `Shipment: ${failed.shipmentId ?? 'unknown (payload could not be parsed)'}`,
        'Reprocessing is idempotent: an event already stored comes back as a duplicate.',
      ],
      confirmLabel: 'Retry now',
    };
    this.dialog
      .open<ConfirmDialog, ConfirmDialogData, boolean>(ConfirmDialog, { data, width: '480px', maxWidth: '95vw' })
      .afterClosed()
      .pipe(
        filter((confirmed) => confirmed === true),
        switchMap(() => {
          this.retrying.set(true);
          this.outcome.set(null);
          return this.api.retry(failed.failedEventId).pipe(finalize(() => this.retrying.set(false)));
        }),
      )
      .subscribe({
        next: (response) => {
          this.outcome.set(interpretRetry(response));
          this.event.reload();
        },
        error: (err: unknown) => {
          this.outcome.set(interpretRetry(toApiError(err)));
          this.event.reload();
        },
      });
  }
}
