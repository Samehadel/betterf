import type { CapturePage } from './capture-page';
import { inject } from '@angular/core';
import { CanActivateFn, CanDeactivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { AuthStore } from '../core/auth.store';
export const knowledgeAccess: CanActivateFn = () => {
  const auth = inject(AuthStore);
  const router = inject(Router);
  return auth
    .load()
    .pipe(map((account) => !!account || router.createUrlTree(['/login'])));
};

export const unsavedCapture: CanDeactivateFn<CapturePage> = (page) =>
  page.canLeave();
