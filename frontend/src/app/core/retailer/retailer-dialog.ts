import { inject, Injectable } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';

/**
 * Opens the retailer picker. The dialog (and the forms/chips code it pulls in) is loaded on first
 * use, keeping it out of the initial bundle.
 */
@Injectable({ providedIn: 'root' })
export class RetailerDialogLauncher {
  private readonly dialog = inject(MatDialog);

  async open(): Promise<void> {
    const { RetailerDialog } = await import('./retailer-dialog.component');
    this.dialog.open(RetailerDialog, { width: '440px', maxWidth: '95vw', autoFocus: 'first-tabbable' });
  }
}
