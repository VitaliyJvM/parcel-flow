import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { Router } from '@angular/router';
import { finalize } from 'rxjs';
import { ApiError, toApiError } from '../../core/api/api-error';
import { CarrierCode } from '../../core/api/models';
import { ShipmentsApi } from '../../core/api/tracking-api';
import { CARRIER_META, CARRIERS, TRACKING_NUMBER_MAX_LENGTH } from '../../shared/domain';
import { ErrorPanel } from '../../shared/error-panel';
import { applyServerErrors, notBlank } from '../../shared/validators';

export type LookupForm = FormGroup<{
  carrierCode: FormControl<CarrierCode>;
  trackingNumber: FormControl<string>;
}>;

export function createLookupForm(): LookupForm {
  return new FormGroup({
    carrierCode: new FormControl<CarrierCode>('SWIFTPOST', {
      nonNullable: true,
      validators: [Validators.required],
    }),
    trackingNumber: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, notBlank, Validators.maxLength(TRACKING_NUMBER_MAX_LENGTH)],
    }),
  });
}

/**
 * Trims the tracking number, exactly as the registration form does before it posts. The backend
 * matches verbatim, so trimming here is what makes "paste with a trailing space" find the shipment
 * this UI registered — and it cannot produce a false match, because this UI never stores padding.
 */
export function toLookupQuery(value: ReturnType<LookupForm['getRawValue']>): {
  carrierCode: CarrierCode;
  trackingNumber: string;
} {
  return { carrierCode: value.carrierCode, trackingNumber: value.trackingNumber.trim() };
}

/**
 * "Find by tracking number": resolves a carrier's tracking number through
 * `GET /api/shipments/lookup` and opens the shipment's detail page.
 *
 * Not scoped to the selected retailer, like "Open by shipment ID": the backend has no ownership
 * model yet (see the README's limitations), and pretending otherwise in the UI would be a view
 * filter dressed up as access control.
 */
@Component({
  selector: 'pf-shipment-lookup-form',
  imports: [
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    ErrorPanel,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="pf-card pf-lookup" aria-labelledby="lookup-heading">
      <h2 id="lookup-heading" class="pf-lookup-heading">Find by tracking number</h2>
      <form
        class="pf-lookup-form"
        [formGroup]="form"
        (ngSubmit)="submit()"
        novalidate
        role="search"
        aria-labelledby="lookup-heading"
      >
        <mat-form-field subscriptSizing="dynamic" class="pf-lookup-carrier">
          <mat-label>Carrier</mat-label>
          <mat-select formControlName="carrierCode" required>
            @for (carrier of carriers; track carrier) {
              <mat-option [value]="carrier">{{ carrierMeta[carrier].label }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-form-field subscriptSizing="dynamic" class="pf-lookup-number" hideRequiredMarker>
          <mat-label>Tracking number</mat-label>
          <input
            matInput
            formControlName="trackingNumber"
            [maxlength]="maxLength"
            autocomplete="off"
            spellcheck="false"
            class="pf-mono"
            required
          />
          @if (
            form.controls.trackingNumber.hasError('required') ||
            form.controls.trackingNumber.hasError('blank')
          ) {
            <mat-error>Enter a tracking number.</mat-error>
          } @else if (form.controls.trackingNumber.hasError('notFound')) {
            <mat-error data-testid="lookup-not-found">
              No {{ carrierMeta[form.controls.carrierCode.value].label }} shipment has this tracking
              number. Tracking numbers are case-sensitive.
            </mat-error>
          } @else if (form.controls.trackingNumber.hasError('server')) {
            <mat-error>{{ form.controls.trackingNumber.getError('server') }}</mat-error>
          }
        </mat-form-field>
        <button mat-flat-button type="submit" [disabled]="searching()">
          <mat-icon aria-hidden="true">search</mat-icon>
          Find
        </button>
      </form>
      @if (searching()) {
        <mat-progress-bar mode="indeterminate" aria-label="Looking up shipment" />
      }
      @if (error(); as e) {
        <pf-error-panel [error]="e" context="Lookup failed" [retryable]="true" (retry)="submit()" />
      }
    </section>
  `,
  styles: `
    .pf-lookup-heading {
      font: var(--mat-sys-title-small);
      margin: 0 0 8px;
    }
    .pf-lookup-form {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      gap: 8px 12px;
    }
    .pf-lookup-carrier {
      width: 180px;
    }
    .pf-lookup-number {
      flex: 1 1 260px;
      max-width: 420px;
    }
    .pf-lookup-form > button {
      margin-top: 8px;
    }
    pf-error-panel {
      display: block;
      margin-top: 12px;
    }
    @media (max-width: 599.98px) {
      .pf-lookup-carrier,
      .pf-lookup-number {
        flex: 1 1 100%;
        width: auto;
        max-width: none;
      }
    }
  `,
})
export class ShipmentLookupForm {
  private readonly api = inject(ShipmentsApi);
  private readonly router = inject(Router);

  protected readonly carriers = CARRIERS;
  protected readonly carrierMeta = CARRIER_META;
  protected readonly maxLength = TRACKING_NUMBER_MAX_LENGTH;

  protected readonly form = createLookupForm();
  protected readonly searching = signal(false);
  /** Failures that are not about a field: network, 5xx, unexpected problem types. */
  protected readonly error = signal<ApiError | null>(null);

  constructor() {
    // A "not found" is a statement about the (carrier, number) pair. Changing the carrier
    // re-runs the tracking number's validators, which drops the stale server-side flag.
    this.form.controls.carrierCode.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.form.controls.trackingNumber.updateValueAndValidity());
  }

  protected submit(): void {
    if (this.searching()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.error.set(null);
    this.searching.set(true);
    const { carrierCode, trackingNumber } = toLookupQuery(this.form.getRawValue());
    this.api
      .lookup(carrierCode, trackingNumber)
      .pipe(finalize(() => this.searching.set(false)))
      .subscribe({
        next: (shipment) => void this.router.navigate(['/shipments', shipment.shipmentId]),
        error: (err: unknown) => this.handleError(toApiError(err)),
      });
  }

  private handleError(error: ApiError): void {
    const control = this.form.controls.trackingNumber;
    if (error.status === 404 && error.slug === 'shipment-not-found') {
      control.setErrors({ ...(control.errors ?? {}), notFound: true });
      control.markAsTouched();
      return;
    }
    if (error.status === 400 && Object.keys(error.fieldErrors).length > 0) {
      if (Object.keys(applyServerErrors(this.form, error)).length > 0) {
        this.error.set(error);
      }
      return;
    }
    this.error.set(error);
  }
}
