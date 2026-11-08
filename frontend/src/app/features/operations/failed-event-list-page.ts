import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { toApiError } from '../../core/api/api-error';
import { ErrorCategory, FailedEventStatus, PAGE_LIMITS } from '../../core/api/models';
import { FailedEventsApi } from '../../core/api/tracking-api';
import { InstantDisplay } from '../../shared/date-display';
import {
  ERROR_CATEGORY_META,
  FAILED_EVENT_STATUS_META,
  FAILED_EVENT_STATUSES,
  shortId,
} from '../../shared/domain';
import { ErrorPanel } from '../../shared/error-panel';
import { Tag } from '../../shared/status-chip';
import { DEFAULT_PAGE_SIZE } from '../shipments/list-query';

export function parseFailedEventQuery(
  status: string | undefined,
  page: string | undefined,
  size: string | undefined,
): { status: FailedEventStatus | null; page: number; size: number } {
  const p = Number.parseInt(page ?? '', 10);
  const s = Number.parseInt(size ?? '', 10);
  return {
    status: FAILED_EVENT_STATUSES.find((x) => x === status) ?? null,
    page: Number.isFinite(p) && p > 0 ? p : 0,
    size: Number.isFinite(s) && s > 0 ? Math.min(s, PAGE_LIMITS.failedEvents) : DEFAULT_PAGE_SIZE,
  };
}

@Component({
  selector: 'pf-failed-event-list-page',
  imports: [
    RouterLink,
    MatTableModule,
    MatPaginatorModule,
    MatSelectModule,
    MatFormFieldModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule,
    InstantDisplay,
    ErrorPanel,
    Tag,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './failed-event-list-page.html',
  styleUrl: './failed-event-list-page.scss',
})
export class FailedEventListPage {
  private readonly api = inject(FailedEventsApi);
  private readonly router = inject(Router);

  readonly status = input<string | undefined>();
  readonly page = input<string | undefined>();
  readonly size = input<string | undefined>();

  protected readonly query = computed(() =>
    parseFailedEventQuery(this.status(), this.page(), this.size()),
  );
  protected readonly statuses = FAILED_EVENT_STATUSES;
  protected readonly allStatuses = 'ALL';
  protected readonly statusMeta = FAILED_EVENT_STATUS_META;
  protected readonly shortId = shortId;
  protected readonly columns = ['lastFailed', 'category', 'status', 'retry', 'shipment', 'error'];

  protected readonly events = rxResource({
    params: () => this.query(),
    stream: ({ params }) => this.api.list({ page: params.page, size: params.size }, params.status),
  });
  protected readonly error = computed(() => {
    const e = this.events.error();
    return e ? toApiError(e) : null;
  });

  protected category(code: ErrorCategory) {
    return ERROR_CATEGORY_META[code];
  }

  protected statusOf(status: FailedEventStatus) {
    return FAILED_EVENT_STATUS_META[status];
  }

  protected filterBy(status: FailedEventStatus | null): void {
    void this.router.navigate([], { queryParams: { status, page: null }, queryParamsHandling: 'merge' });
  }

  /** Recovery from an out-of-range page; page 0 is written as "no page parameter". */
  protected goToPage(index: number): void {
    void this.router.navigate([], {
      queryParams: { page: index > 0 ? index : null },
      queryParamsHandling: 'merge',
    });
  }

  protected onPage(event: PageEvent): void {
    void this.router.navigate([], {
      queryParams: { page: event.pageIndex || null, size: event.pageSize },
      queryParamsHandling: 'merge',
    });
  }

  protected open(id: string): void {
    void this.router.navigate(['/operations/failed-events', id]);
  }
}
