import type { CapturePage } from './capture-page';
import { inject } from '@angular/core';
import { CanActivateFn, CanDeactivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { IdentityApi } from '../identity/identity.api';
import { AuthStore } from '../core/auth.store';
export const knowledgeAccess: CanActivateFn = () => {
  const auth = inject(AuthStore);
  const router = inject(Router);
  // Revalidate membership before displaying any restored workspace content.
  const api = inject(IdentityApi);
  const workspaceId = auth.account()?.workspaceId;
  if (auth.account() && !workspaceId)
    return router.createUrlTree(['/workspace']);
  return (workspaceId ? api.selectWorkspace(workspaceId) : api.current()).pipe(
    map((account) => {
      auth.setAccount(account);
      return account.workspaceId ? true : router.createUrlTree(['/workspace']);
    }),
    catchError(() => {
      auth.clear();
      return of(router.createUrlTree(['/login']));
    }),
  );
};

export const unsavedCapture: CanDeactivateFn<CapturePage> = (page) =>
  page.canLeave();
