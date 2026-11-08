import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { notBlank } from '../../shared/validators';
import { RETAILER_ID_MAX_LENGTH, RetailerContext } from './retailer-context';

@Component({
  selector: 'pf-retailer-dialog',
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatChipsModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form (submit)="$event.preventDefault(); save()" novalidate>
      <h2 mat-dialog-title>Retailer context</h2>
      <mat-dialog-content>
        <p class="pf-muted">
          Scopes shipment lists and the dashboard to one retailer ID. This is a view filter, not a
          login: the demo backend has no authentication.
        </p>
        <mat-form-field appearance="outline" class="pf-full">
          <mat-label>Retailer ID</mat-label>
          <input
            matInput
            [formControl]="control"
            [maxlength]="maxLength"
            autocomplete="off"
            cdkFocusInitial
            placeholder="retailer-42"
          />
          <mat-hint align="end">{{ control.value.length }}/{{ maxLength }}</mat-hint>
          @if (control.hasError('required') || control.hasError('blank')) {
            <mat-error>Enter a retailer ID.</mat-error>
          }
        </mat-form-field>
        @if (context.recent().length > 0) {
          <p class="pf-label">Recent</p>
          <mat-chip-set aria-label="Recent retailer IDs">
            @for (id of context.recent(); track id) {
              <mat-chip (click)="pick(id)" (keydown.enter)="pick(id)" tabindex="0" role="button">
                {{ id }}
              </mat-chip>
            }
          </mat-chip-set>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancel</button>
        <button mat-flat-button type="submit" [disabled]="control.invalid">Use retailer</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class RetailerDialog {
  protected readonly context = inject(RetailerContext);
  private readonly ref = inject(MatDialogRef<RetailerDialog>);
  protected readonly maxLength = RETAILER_ID_MAX_LENGTH;
  protected readonly control = new FormControl(this.context.retailerId() ?? '', {
    nonNullable: true,
    validators: [Validators.required, notBlank, Validators.maxLength(RETAILER_ID_MAX_LENGTH)],
  });

  protected pick(id: string): void {
    this.context.select(id);
    this.ref.close(id);
  }

  protected save(): void {
    if (this.control.valid) {
      this.pick(this.control.value);
    }
  }
}
