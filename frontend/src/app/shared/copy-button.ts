import { Clipboard } from '@angular/cdk/clipboard';
import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

@Component({
  selector: 'pf-copy-button',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button
      mat-icon-button
      type="button"
      class="pf-copy"
      [attr.aria-label]="label()"
      [matTooltip]="copied() ? 'Copied' : label()"
      (click)="copy()"
    >
      <mat-icon aria-hidden="true">{{ copied() ? 'check' : 'content_copy' }}</mat-icon>
    </button>
    <span class="pf-visually-hidden" aria-live="polite">{{ copied() ? 'Copied' : '' }}</span>
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      vertical-align: middle;
    }
    .pf-copy {
      --mat-icon-button-state-layer-size: 32px;
      padding: 4px;
    }
    .pf-copy mat-icon {
      font-size: 18px;
      width: 18px;
      height: 18px;
    }
  `,
})
export class CopyButton {
  private readonly clipboard = inject(Clipboard);
  readonly value = input.required<string>();
  readonly label = input('Copy');
  protected readonly copied = signal(false);
  private resetTimer: ReturnType<typeof setTimeout> | undefined;

  copy(): void {
    const ok = this.clipboard.copy(this.value());
    this.copied.set(ok);
    clearTimeout(this.resetTimer);
    this.resetTimer = setTimeout(() => this.copied.set(false), 2000);
  }
}
