import { BreakpointObserver } from '@angular/cdk/layout';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSidenav, MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { map } from 'rxjs';
import { Role } from './core/api/models';
import { AuthSession } from './core/auth/auth-session';
import { RetailerContext } from './core/retailer/retailer-context';
import { RetailerDialogLauncher } from './core/retailer/retailer-dialog';

interface NavItem {
  path: string;
  label: string;
  icon: string;
  exact?: boolean;
  /** Shown only to this role. Visibility only: the backend enforces the same rule. */
  role?: Role;
}

export const NAV_ITEMS: readonly NavItem[] = [
  { path: '/dashboard', label: 'Overview', icon: 'space_dashboard' },
  { path: '/shipments', label: 'Shipments', icon: 'inventory_2', exact: true },
  { path: '/shipments/new', label: 'Register shipment', icon: 'add_box' },
  { path: '/operations/failed-events', label: 'Failed events', icon: 'report', role: 'ADMIN' },
  { path: '/simulator', label: 'Carrier simulator', icon: 'terminal', role: 'ADMIN' },
  { path: '/about', label: 'About & security', icon: 'info' },
];

@Component({
  selector: 'pf-root',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatToolbarModule,
    MatSidenavModule,
    MatListModule,
    MatIconModule,
    MatButtonModule,
    MatMenuModule,
    MatProgressBarModule,
    MatTooltipModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  protected readonly session = inject(AuthSession);
  protected readonly retailer = inject(RetailerContext);
  protected readonly retailerDialog = inject(RetailerDialogLauncher);
  private readonly sidenav = viewChild(MatSidenav);

  protected readonly signingOut = signal(false);

  protected readonly isHandset = toSignal(
    inject(BreakpointObserver)
      .observe('(max-width: 959.98px)')
      .pipe(map((state) => state.matches)),
    { initialValue: false },
  );
  protected readonly sidenavMode = computed(() => (this.isHandset() ? 'over' : 'side'));

  protected readonly nav = computed(() => {
    const role = this.session.role();
    return NAV_ITEMS.filter((item) => !item.role || item.role === role);
  });

  protected closeOnHandset(): void {
    if (this.isHandset()) {
      void this.sidenav()?.close();
    }
  }

  protected async signOut(): Promise<void> {
    if (this.signingOut()) {
      return;
    }
    this.signingOut.set(true);
    try {
      await this.session.logout();
    } catch {
      // The CSRF token could not be fetched (backend down). Nothing was submitted; let the user retry.
      this.signingOut.set(false);
    }
  }
}
