import { Routes } from '@angular/router';
import { StatusStore } from './foundation/status.store';

export const routes: Routes = [
  { path: '', pathMatch: 'full', providers: [StatusStore], loadComponent: () => import('./foundation/status-page').then(m => m.StatusPage) },
  { path: '**', redirectTo: '' },
];
