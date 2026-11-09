import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'pf-forbidden-page',
  imports: [RouterLink, MatButtonModule, MatIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="pf-empty">
      <mat-icon aria-hidden="true">lock</mat-icon>
      <h1 class="pf-title">Not permitted</h1>
      <p>This page is for ParcelFlow operators (ADMIN). Your account does not have that role.</p>
      <a mat-flat-button routerLink="/dashboard">Go to overview</a>
    </div>
  `,
})
export class ForbiddenPage {}
