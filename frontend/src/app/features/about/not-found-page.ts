import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'pf-not-found-page',
  imports: [RouterLink, MatButtonModule, MatIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="pf-empty">
      <mat-icon aria-hidden="true">explore_off</mat-icon>
      <h1 class="pf-title">Page not found</h1>
      <p>There is no page at this address.</p>
      <a mat-flat-button routerLink="/dashboard">Go to overview</a>
    </div>
  `,
})
export class NotFoundPage {}
