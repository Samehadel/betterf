import type { CapturePage } from './capture-page';
import { inject } from '@angular/core';
import { CanActivateFn, CanDeactivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { IdentityApi } from '../identity/identity.api';
import { AuthStore } from '../core/auth.store';
export const knowledgeAccess: CanActivateFn = () => {
  const auth = inject(AuthStore);
  const router = inject(Router);
  // Revalidate membership before displaying any restored organization content.
  return inject(IdentityApi)
    .current()
    .pipe(
      map((account) => {
        auth.setAccount(account);
        return true;
      }),
      catchError(() => {
        auth.clear();
        return of(router.createUrlTree(['/login']));
      }),
    );
};

export const unsavedCapture: CanDeactivateFn<CapturePage> = (page) =>
  page.canLeave();
