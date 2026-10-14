import { TeamManagementStore } from './invitations/team-management.store';
import { AcceptanceStore } from './invitations/acceptance.store';
import { InvitationStore } from './invitations/invitation.store';
import { IdentityStore } from './identity/identity.store';
import { Routes } from '@angular/router';
import { StatusStore } from './foundation/status.store';

export const routes: Routes = [
  {
    path: 'invitation/accept',
    providers: [AcceptanceStore],
    loadComponent: () =>
      import('./invitations/acceptance-page').then((m) => m.AcceptancePage),
  },
  {
    path: '',
    pathMatch: 'full',
    loadComponent: () =>
      import('./landing/landing-page').then((m) => m.LandingPage),
  },
  {
    path: 'status',
    providers: [StatusStore],
    loadComponent: () =>
      import('./foundation/status-page').then((m) => m.StatusPage),
  },
  {
    path: 'company/invitations/pending',
    redirectTo: 'company/team',
    pathMatch: 'full',
  },
  {
    path: 'company/team',
    providers: [TeamManagementStore],
    loadComponent: () =>
      import('./invitations/team-management-page').then(
        (m) => m.TeamManagementPage,
      ),
  },
  {
    path: 'company/invitations',
    providers: [InvitationStore],
    loadComponent: () =>
      import('./invitations/invitation-page').then((m) => m.InvitationPage),
  },
  ...(['register', 'verify', 'login', 'company'] as const).map((mode) => ({
    path: mode,
    data: { mode },
    providers: [IdentityStore],
    loadComponent: () =>
      import('./identity/identity-page').then((m) => m.IdentityPage),
  })),
  { path: '**', redirectTo: '' },
];
