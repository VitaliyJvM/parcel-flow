import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { of } from 'rxjs';
import { toApiError } from '../../core/api/api-error';
import { CarrierCode, PAGE_LIMITS, ShipmentStatus } from '../../core/api/models';
import { ShipmentsApi } from '../../core/api/tracking-api';
import { RetailerContext } from '../../core/retailer/retailer-context';
import { InstantDisplay, LocalDateDisplay } from '../../shared/date-display';
import { CARRIER_META, SHIPMENT_STATUS_META, SHIPMENT_STATUSES } from '../../shared/domain';
import { ErrorPanel } from '../../shared/error-panel';
import { RetailerRequired } from '../../shared/retailer-required';
import { StatusChip } from '../../shared/status-chip';
import { parseListQuery } from './list-query';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

@Component({
  selector: 'pf-shipment-list-page',
  imports: [
    RouterLink,
    ReactiveFormsModule,
    MatTableModule,
    MatPaginatorModule,
    MatSelectModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule,
    StatusChip,
    InstantDisplay,
    LocalDateDisplay,
    ErrorPanel,
    RetailerRequired,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './shipment-list-page.html',
  styleUrl: './shipment-list-page.scss',
})
export class ShipmentListPage {
  private readonly api = inject(ShipmentsApi);
  private readonly router = inject(Router);
  protected readonly retailer = inject(RetailerContext);

  // Query parameters, bound by the router (withComponentInputBinding). The URL is the state, so
  // a filtered page is linkable and survives reload / back navigation.
  readonly status = input<string | undefined>();
  readonly page = input<string | undefined>();
  readonly size = input<string | undefined>();

  protected readonly query = computed(() =>
    parseListQuery(this.status(), this.page(), this.size(), PAGE_LIMITS.retailerShipments),
  );

  protected readonly statuses = SHIPMENT_STATUSES;
  protected readonly allStatuses = 'ALL';
  protected readonly statusMeta = SHIPMENT_STATUS_META;
  protected readonly columns = ['status', 'trackingNumber', 'carrier', 'eta', 'lastEvent', 'created'];

  protected readonly shipments = rxResource({
    params: () => ({ retailerId: this.retailer.retailerId(), ...this.query() }),
    stream: ({ params }) =>
      params.retailerId === null
        ? of(null)
        : this.api.listForRetailer(
            params.retailerId,
            { page: params.page, size: params.size },
            params.status,
          ),
  });
  protected readonly error = computed(() => {
    const e = this.shipments.error();
    return e ? toApiError(e) : null;
  });

  protected readonly openById = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, Validators.pattern(UUID_PATTERN)],
  });

  protected carrierLabel(code: CarrierCode): string {
    return CARRIER_META[code].label;
  }

  protected filterBy(status: ShipmentStatus | null): void {
    void this.navigate({ status, page: null });
  }

  /** Recovery from an out-of-range page; page 0 is written as "no page parameter". */
  protected goToPage(index: number): void {
    void this.navigate({ page: index > 0 ? index : null });
  }

  protected onPage(event: PageEvent): void {
    void this.navigate({ page: event.pageIndex || null, size: event.pageSize });
  }

  protected open(shipmentId: string): void {
    void this.router.navigate(['/shipments', shipmentId]);
  }

  protected submitOpenById(): void {
    if (this.openById.valid) {
      this.open(this.openById.value.trim().toLowerCase());
    }
  }

  private navigate(queryParams: Record<string, string | number | null>): Promise<boolean> {
    return this.router.navigate([], { queryParams, queryParamsHandling: 'merge' });
  }
}
