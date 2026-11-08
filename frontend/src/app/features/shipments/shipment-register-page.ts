import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { ApiError, toApiError } from '../../core/api/api-error';
import { CarrierCode, RegisterShipmentRequest } from '../../core/api/models';
import { ShipmentsApi } from '../../core/api/tracking-api';
import { RetailerContext } from '../../core/retailer/retailer-context';
import { isLocalDateBefore, todayLocalDate } from '../../shared/dates';
import { CARRIER_META, CARRIERS, isOperationalCarrier } from '../../shared/domain';
import { ErrorPanel } from '../../shared/error-panel';
import { applyServerErrors, notBlank } from '../../shared/validators';

/** Limits copied from `RegisterShipmentRequest` (@Size(max = ...)). */
export const REGISTER_LIMITS = { retailerId: 64, customerId: 64, trackingNumber: 128 } as const;

export type RegisterForm = FormGroup<{
  retailerId: FormControl<string>;
  customerId: FormControl<string>;
  trackingNumber: FormControl<string>;
  carrierCode: FormControl<CarrierCode>;
  estimatedDeliveryDate: FormControl<string>;
}>;

export function createRegisterForm(retailerId: string): RegisterForm {
  const text = (max: number, initial = '') =>
    new FormControl(initial, {
      nonNullable: true,
      validators: [Validators.required, notBlank, Validators.maxLength(max)],
    });
  return new FormGroup({
    retailerId: text(REGISTER_LIMITS.retailerId, retailerId),
    customerId: text(REGISTER_LIMITS.customerId),
    trackingNumber: text(REGISTER_LIMITS.trackingNumber),
    carrierCode: new FormControl<CarrierCode>('SWIFTPOST', {
      nonNullable: true,
      validators: [Validators.required],
    }),
    // <input type="date"> yields 'YYYY-MM-DD' (or ''), which is exactly the LocalDate wire
    // format. It never passes through a Date object, so there is no time-zone shift to undo.
    estimatedDeliveryDate: new FormControl('', { nonNullable: true }),
  });
}

/** Trims identifiers (the backend's @NotBlank accepts padded values verbatim) and drops an empty ETA. */
export function toRegisterRequest(value: ReturnType<RegisterForm['getRawValue']>): RegisterShipmentRequest {
  const request: RegisterShipmentRequest = {
    retailerId: value.retailerId.trim(),
    customerId: value.customerId.trim(),
    trackingNumber: value.trackingNumber.trim(),
    carrierCode: value.carrierCode,
  };
  if (value.estimatedDeliveryDate) {
    request.estimatedDeliveryDate = value.estimatedDeliveryDate;
  }
  return request;
}

@Component({
  selector: 'pf-shipment-register-page',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    ErrorPanel,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './shipment-register-page.html',
  styleUrl: './shipment-register-page.scss',
})
export class ShipmentRegisterPage {
  private readonly api = inject(ShipmentsApi);
  private readonly router = inject(Router);
  private readonly retailer = inject(RetailerContext);

  protected readonly limits = REGISTER_LIMITS;
  protected readonly carriers = CARRIERS;
  protected readonly carrierMeta = CARRIER_META;
  protected readonly isOperational = isOperationalCarrier;
  protected readonly today = todayLocalDate();

  protected readonly form = createRegisterForm(this.retailer.retailerId() ?? '');
  protected readonly submitting = signal(false);
  /** Errors that did not map onto a field (non-validation failures, unknown fields). */
  protected readonly formError = signal<ApiError | null>(null);
  protected readonly duplicate = signal<{ carrier: string; trackingNumber: string } | null>(null);

  private readonly formValue = toSignal(this.form.valueChanges, { initialValue: this.form.value });
  protected readonly etaInPast = computed(() => {
    const eta = this.formValue().estimatedDeliveryDate;
    return !!eta && isLocalDateBefore(eta, this.today);
  });

  protected submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    this.formError.set(null);
    this.duplicate.set(null);
    this.submitting.set(true);
    const request = toRegisterRequest(this.form.getRawValue());
    this.api
      .register(request)
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: (shipment) => {
          this.retailer.select(shipment.retailerId);
          void this.router.navigate(['/shipments', shipment.shipmentId], {
            state: { justRegistered: true },
          });
        },
        error: (err: unknown) => this.handleError(toApiError(err), request),
      });
  }

  private handleError(error: ApiError, request: RegisterShipmentRequest): void {
    if (error.status === 409 && error.slug === 'duplicate-tracking-number') {
      const trackingNumber = error.extension('trackingNumber') ?? request.trackingNumber;
      const carrier = error.extension('carrierCode') ?? request.carrierCode;
      this.duplicate.set({ carrier, trackingNumber });
      const control = this.form.controls.trackingNumber;
      control.setErrors({ ...(control.errors ?? {}), duplicate: true });
      control.markAsTouched();
      return;
    }
    if (error.status === 400 && Object.keys(error.fieldErrors).length > 0) {
      const unmatched = applyServerErrors(this.form, error);
      if (Object.keys(unmatched).length > 0) {
        this.formError.set(error);
      }
      return;
    }
    this.formError.set(error);
  }
}
