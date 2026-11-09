import { Routes } from '@angular/router';
import { authGuard, landingGuard, roleGuard, signedOutOnlyGuard } from './core/auth/auth-guards';

/**
 * Every feature is lazy-loaded. Titles follow "<page> · ParcelFlow" via the router's title
 * strategy so screen readers announce navigation.
 *
 * Three public routes (login, signed-out, forbidden); everything else needs a session, and the
 * operator pages need ADMIN. Guards decide what to render — the backend authorizes every request
 * regardless of how a page was reached.
 */
export const routes: Routes = [
  {
    path: 'login',
    title: 'Sign in · ParcelFlow',
    canActivate: [signedOutOnlyGuard],
    loadComponent: () => import('./features/auth/login-page').then((m) => m.LoginPage),
  },
  {
    path: 'signed-out',
    title: 'Signed out · ParcelFlow',
    loadComponent: () => import('./features/auth/signed-out-page').then((m) => m.SignedOutPage),
  },
  {
    path: '',
    canActivateChild: [authGuard],
    children: [
      { path: '', pathMatch: 'full', canActivate: [landingGuard], children: [] },
      {
        path: 'dashboard',
        title: 'Overview · ParcelFlow',
        loadComponent: () =>
          import('./features/dashboard/dashboard-page').then((m) => m.DashboardPage),
      },
      {
        path: 'shipments',
        title: 'Shipments · ParcelFlow',
        loadComponent: () =>
          import('./features/shipments/shipment-list-page').then((m) => m.ShipmentListPage),
      },
      {
        path: 'shipments/new',
        title: 'Register shipment · ParcelFlow',
        loadComponent: () =>
          import('./features/shipments/shipment-register-page').then((m) => m.ShipmentRegisterPage),
      },
      {
        path: 'shipments/:shipmentId',
        title: 'Shipment · ParcelFlow',
        loadComponent: () =>
          import('./features/shipments/detail/shipment-detail-page').then(
            (m) => m.ShipmentDetailPage,
          ),
      },
      {
        path: 'operations/failed-events',
        title: 'Failed events · ParcelFlow',
        canActivate: [roleGuard('ADMIN')],
        loadComponent: () =>
          import('./features/operations/failed-event-list-page').then((m) => m.FailedEventListPage),
      },
      {
        path: 'operations/failed-events/:failedEventId',
        title: 'Failed event · ParcelFlow',
        canActivate: [roleGuard('ADMIN')],
        loadComponent: () =>
          import('./features/operations/failed-event-detail-page').then(
            (m) => m.FailedEventDetailPage,
          ),
      },
      {
        path: 'simulator',
        title: 'Carrier simulator · ParcelFlow',
        canActivate: [roleGuard('ADMIN')],
        loadComponent: () =>
          import('./features/simulator/simulator-page').then((m) => m.SimulatorPage),
      },
      {
        path: 'about',
        title: 'About · ParcelFlow',
        loadComponent: () => import('./features/about/about-page').then((m) => m.AboutPage),
      },
      {
        path: 'forbidden',
        title: 'Not permitted · ParcelFlow',
        loadComponent: () => import('./features/auth/forbidden-page').then((m) => m.ForbiddenPage),
      },
      {
        path: '**',
        title: 'Not found · ParcelFlow',
        loadComponent: () => import('./features/about/not-found-page').then((m) => m.NotFoundPage),
      },
    ],
  },
];
