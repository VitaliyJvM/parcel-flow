import { BreakpointObserver } from '@angular/cdk/layout';
import { ChangeDetectionStrategy, Component, computed, inject, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatSidenav, MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { map } from 'rxjs';
import { RetailerContext } from './core/retailer/retailer-context';
import { RetailerDialogLauncher } from './core/retailer/retailer-dialog';

interface NavItem {
  path: string;
  label: string;
  icon: string;
  exact?: boolean;
}

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
    MatTooltipModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  protected readonly retailer = inject(RetailerContext);
  protected readonly retailerDialog = inject(RetailerDialogLauncher);
  private readonly sidenav = viewChild.required(MatSidenav);

  protected readonly isHandset = toSignal(
    inject(BreakpointObserver)
      .observe('(max-width: 959.98px)')
      .pipe(map((state) => state.matches)),
    { initialValue: false },
  );
  protected readonly sidenavMode = computed(() => (this.isHandset() ? 'over' : 'side'));

  protected readonly nav: NavItem[] = [
    { path: '/dashboard', label: 'Overview', icon: 'space_dashboard' },
    { path: '/shipments', label: 'Shipments', icon: 'inventory_2', exact: true },
    { path: '/shipments/new', label: 'Register shipment', icon: 'add_box' },
    { path: '/operations/failed-events', label: 'Failed events', icon: 'report' },
    { path: '/simulator', label: 'Carrier simulator', icon: 'terminal' },
    { path: '/about', label: 'About this demo', icon: 'info' },
  ];

  protected closeOnHandset(): void {
    if (this.isHandset()) {
      void this.sidenav().close();
    }
  }
}
