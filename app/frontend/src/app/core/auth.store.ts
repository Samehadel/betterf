import { inject } from '@angular/core';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { catchError, finalize, Observable, of, shareReplay, tap } from 'rxjs';
import { Account, IdentityApi } from '../identity/identity.api';

/** Owns the in-memory account for the lifetime of the application. */
export const AuthStore = signalStore(
  { providedIn: 'root' },
  withState({
    account: null as Account | null,
    initialized: false,
    loading: false,
    error: '',
    errorCode: '',
  }),
  withMethods((store, api = inject(IdentityApi)) => {
    let pending: Observable<Account | null> | undefined;
    let revision = 0;

    function setAccount(account: Account | null) {
      revision++;
      patchState(store, {
        account,
        initialized: true,
        loading: false,
        error: '',
        errorCode: '',
      });
    }

    /** Shares startup/retry requests and ignores results superseded by session changes. */
    function load(): Observable<Account | null> {
      if (store.initialized()) return of(store.account());
      if (pending) return pending;
      const startedAt = revision;
      patchState(store, { loading: true, error: '', errorCode: '' });
      const request = api.current().pipe(
        tap((account) => {
          if (revision === startedAt) setAccount(account);
        }),
        catchError((error) => {
          if (revision === startedAt) {
            const denied = error?.status === 401 || error?.status === 403;
            patchState(store, {
              account: null,
              initialized: denied,
              error: error?.error?.error?.message ?? '',
              errorCode: error?.error?.error?.code ?? 'UNAVAILABLE',
            });
          }
          return of(null);
        }),
        finalize(() => {
          if (pending === request) pending = undefined;
          if (revision === startedAt) patchState(store, { loading: false });
        }),
        shareReplay({ bufferSize: 1, refCount: false }),
      );
      pending = request;
      return request;
    }

    return { load, setAccount, clear: () => setAccount(null) };
  }),
);
