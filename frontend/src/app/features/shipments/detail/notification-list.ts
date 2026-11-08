import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import type { NotificationResponse, TrackingEventResponse } from '../../../core/api/models';
import { InstantDisplay } from '../../../shared/date-display';
import { NOTIFICATION_TYPE_LABEL } from '../../../shared/domain';
import { Tag } from '../../../shared/status-chip';

/**
 * Notification *records*. The backend creates one per notifiable milestone and never sends
 * anything: every record is `PENDING` on channel `EMAIL` forever (NotificationStatus has one
 * value). The UI says so plainly rather than rendering "Email" as if a message went out.
 */
@Component({
  selector: 'pf-notification-list',
  imports: [MatIconModule, InstantDisplay, Tag],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="pf-callout pf-callout-info" role="note">
      <mat-icon aria-hidden="true">mark_email_unread</mat-icon>
      <div class="pf-callout-body">
        <strong>Recorded, not delivered.</strong> ParcelFlow records milestone notifications but
        sends no email or SMS, so every record stays <code>PENDING</code>.
      </div>
    </div>

    @if (notifications().length === 0) {
      <p class="pf-muted" data-testid="notifications-empty">
        No notification records yet. Label-created, in-transit and facility scans never produce one.
      </p>
    } @else {
      <ul class="pf-notifications" aria-label="Notification records">
        @for (n of rows(); track n.notificationId) {
          <li data-testid="notification-record">
            <div class="pf-n-title">
              <strong>{{ typeLabel[n.notificationType] }}</strong>
              <pf-tag tone="neutral">{{ n.status }} · not sent</pf-tag>
              <span class="pf-muted">would use {{ n.channel }}</span>
            </div>
            <div class="pf-muted pf-n-meta">
              Recorded <pf-instant [value]="n.createdAt" />
              @if (n.source; as source) {
                · triggered by
                <!-- A button, not a fragment link: the target row lives in another tab, which is
                     not rendered while this one is shown. The parent switches tabs and focuses it. -->
                <button
                  type="button"
                  class="pf-link-button"
                  (click)="showEvent.emit(n.sourceEventId)"
                  [attr.aria-label]="'Show event ' + source.carrierEventType + ' #' + source.sequenceNumber + ' in tracking history'"
                >
                  {{ source.carrierEventType }} #{{ source.sequenceNumber }}
                </button>
              } @else {
                · source event <code>{{ n.sourceEventId }}</code>
              }
            </div>
          </li>
        }
      </ul>
    }
  `,
  styles: `
    .pf-notifications {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .pf-notifications li {
      padding: 10px 0;
      border-bottom: 1px solid var(--mat-sys-outline-variant);
    }
    .pf-notifications li:last-child {
      border-bottom: none;
    }
    .pf-n-title {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      align-items: center;
    }
    .pf-n-meta {
      font: var(--mat-sys-body-small);
      margin-top: 4px;
    }
  `,
})
export class NotificationList {
  readonly notifications = input.required<readonly NotificationResponse[]>();
  readonly events = input<readonly TrackingEventResponse[]>([]);
  /** Emits the source event id when the user asks to see it in the tracking history. */
  readonly showEvent = output<string>();
  protected readonly typeLabel = NOTIFICATION_TYPE_LABEL;

  protected readonly rows = computed(() => {
    const byId = new Map(this.events().map((e) => [e.eventId, e]));
    return this.notifications().map((n) => ({ ...n, source: byId.get(n.sourceEventId) ?? null }));
  });
}
