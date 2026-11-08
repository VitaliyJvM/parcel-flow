import { Routes } from '@angular/router';

/**
 * Every feature is lazy-loaded. Titles follow "<page> · ParcelFlow" via the router's title
 * strategy so screen readers announce navigation.
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'dashboard',
    title: 'Overview · ParcelFlow',
    loadComponent: () => import('./features/dashboard/dashboard-page').then((m) => m.DashboardPage),
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
      import('./features/shipments/detail/shipment-detail-page').then((m) => m.ShipmentDetailPage),
  },
  {
    path: 'operations/failed-events',
    title: 'Failed events · ParcelFlow',
    loadComponent: () =>
      import('./features/operations/failed-event-list-page').then((m) => m.FailedEventListPage),
  },
  {
    path: 'operations/failed-events/:failedEventId',
    title: 'Failed event · ParcelFlow',
    loadComponent: () =>
      import('./features/operations/failed-event-detail-page').then((m) => m.FailedEventDetailPage),
  },
  {
    path: 'simulator',
    title: 'Carrier simulator · ParcelFlow',
    loadComponent: () =>
      import('./features/simulator/simulator-page').then((m) => m.SimulatorPage),
  },
  {
    path: 'about',
    title: 'About this demo · ParcelFlow',
    loadComponent: () => import('./features/about/about-page').then((m) => m.AboutPage),
  },
  {
    path: '**',
    title: 'Not found · ParcelFlow',
    loadComponent: () => import('./features/about/not-found-page').then((m) => m.NotFoundPage),
  },
];
