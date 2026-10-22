import { inject } from '@angular/core';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { catchError, EMPTY, exhaustMap, pipe, tap } from 'rxjs';
import { IdentityApi } from './identity.api';
import { AuthStore } from '../core/auth.store';

export const LinkAccountStore = signalStore(
  withState({
    provider: null as string | null,
    busy: false,
    loaded: false,
    linked: false,
    error: '',
    failed: false,
  }),
  withMethods((store, api = inject(IdentityApi), auth = inject(AuthStore)) => ({
    load: rxMethod<void>(
      pipe(
        exhaustMap(() =>
          api.pendingLink().pipe(
            tap((result) =>
              patchState(store, {
                provider: result.provider,
                loaded: true,
                error: '',
                failed: false,
              }),
            ),
            catchError(() => {
              patchState(store, {
                loaded: true,
                failed: true,
              });
              return EMPTY;
            }),
          ),
        ),
      ),
    ),
    confirm: rxMethod<void>(
      pipe(
        exhaustMap(() => {
          patchState(store, { busy: true, error: '', failed: false });
          return api.linkProvider().pipe(
            tap((account) => {
              auth.setAccount(account);
              patchState(store, { linked: true, busy: false });
            }),
            catchError((error) => {
              patchState(store, {
                busy: false,
                error: error?.error?.error?.message ?? '',
                failed: true,
              });
              return EMPTY;
            }),
          );
        }),
      ),
    ),
  })),
);
