import { Routes } from '@angular/router';
import { StatusStore } from './foundation/status.store';

export const routes: Routes = [
  { path: '', pathMatch: 'full', loadComponent: () => import('./landing/landing-page').then(m => m.LandingPage) },
  { path: 'status', providers: [StatusStore], loadComponent: () => import('./foundation/status-page').then(m => m.StatusPage) },
  { path: '**', redirectTo: '' },
];
