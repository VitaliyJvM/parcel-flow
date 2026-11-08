import { AbstractControl, FormGroup, ValidationErrors, ValidatorFn } from '@angular/forms';
import { ApiError } from '../core/api/api-error';

/** Mirrors Bean Validation's @NotBlank: whitespace-only is invalid. Empty is left to `required`. */
export const notBlank: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const value: unknown = control.value;
  return typeof value === 'string' && value.length > 0 && value.trim().length === 0
    ? { blank: true }
    : null;
};

/**
 * Copies a 400 problem's `errors` map onto matching form controls as a `server` error, so the
 * message renders under the field that caused it. Returns the entries no control claimed, which
 * the caller shows as a form-level message.
 */
export function applyServerErrors(form: FormGroup, error: ApiError): Record<string, string> {
  const unmatched: Record<string, string> = {};
  for (const [field, message] of Object.entries(error.fieldErrors)) {
    const control = form.get(field);
    if (control) {
      control.setErrors({ ...(control.errors ?? {}), server: message });
      control.markAsTouched();
    } else {
      unmatched[field] = message;
    }
  }
  return unmatched;
}
